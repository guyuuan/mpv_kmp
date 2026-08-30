#!/usr/bin/env perl

use strict;
use warnings;
use Fcntl qw(SEEK_SET);

sub read_exact {
    my ($fh, $offset, $length, $path) = @_;
    sysseek($fh, $offset, SEEK_SET) == $offset
        or die "Cannot seek to $offset in $path: $!\n";
    my $buffer = '';
    sysread($fh, $buffer, $length) == $length
        or die "Cannot read $length bytes at $offset from $path: $!\n";
    return $buffer;
}

sub write_exact {
    my ($fh, $offset, $buffer, $path) = @_;
    sysseek($fh, $offset, SEEK_SET) == $offset
        or die "Cannot seek to $offset in $path: $!\n";
    syswrite($fh, $buffer) == length($buffer)
        or die "Cannot write at $offset in $path: $!\n";
}

@ARGV == 2 or die "Usage: $0 <image-base> <dll>\n";
my ($image_base_arg, $path) = @ARGV;
my $image_base = $image_base_arg =~ /^0x[0-9a-f]+$/i
    ? hex($image_base_arg)
    : int($image_base_arg);

$image_base > 0 && $image_base < 0x80000000
    or die "PE image base must be between 0 and 0x7fffffff: $image_base_arg\n";
$image_base % 0x10000 == 0
    or die "PE image base must be 64 KiB aligned: $image_base_arg\n";

open my $fh, '+<:raw', $path or die "Cannot open $path: $!\n";
my $pe_offset = unpack('V', read_exact($fh, 0x3c, 4, $path));
read_exact($fh, $pe_offset, 4, $path) eq "PE\0\0"
    or die "Invalid PE signature in $path\n";

my $optional_header = $pe_offset + 24;
unpack('v', read_exact($fh, $optional_header, 2, $path)) == 0x20b
    or die "Expected a PE32+ image: $path\n";

my $old_image_base = unpack(
    'Q<', read_exact($fh, $optional_header + 0x18, 8, $path)
);
my $image_base_delta = $image_base - $old_image_base;

my $section_count = unpack('v', read_exact($fh, $pe_offset + 6, 2, $path));
my $optional_header_size = unpack(
    'v', read_exact($fh, $pe_offset + 20, 2, $path)
);
my $section_table = $optional_header + $optional_header_size;
my @sections;
for my $index (0 .. $section_count - 1) {
    my $section = $section_table + $index * 40;
    my $virtual_size = unpack('V', read_exact($fh, $section + 8, 4, $path));
    my $virtual_address = unpack('V', read_exact($fh, $section + 12, 4, $path));
    my $raw_size = unpack('V', read_exact($fh, $section + 16, 4, $path));
    my $raw_offset = unpack('V', read_exact($fh, $section + 20, 4, $path));
    push @sections, {
        virtual_address => $virtual_address,
        virtual_size => $virtual_size,
        raw_size => $raw_size,
        raw_offset => $raw_offset,
    };
}

sub rva_to_file_offset {
    my ($rva, $length, $sections, $file_path) = @_;
    for my $section (@$sections) {
        my $relative = $rva - $section->{virtual_address};
        next if $relative < 0 || $relative + $length > $section->{raw_size};
        return $section->{raw_offset} + $relative;
    }
    die sprintf "RVA 0x%x is not backed by file data in %s\n", $rva, $file_path;
}

# Rebasing a linked image requires fixing every absolute address recorded in
# its base-relocation directory. Updating only OptionalHeader.ImageBase leaves
# those addresses relative to the linker's old base and makes the Windows
# loader fail from the DLL entry point with ERROR_NOACCESS (998).
if ($image_base_delta != 0) {
    my $base_reloc_directory = $optional_header + 0x70 + 5 * 8;
    my ($reloc_rva, $reloc_size) = unpack(
        'V2', read_exact($fh, $base_reloc_directory, 8, $path)
    );
    $reloc_rva != 0 && $reloc_size >= 8
        or die "Cannot rebase an image without a base-relocation directory: $path\n";

    my $consumed = 0;
    while ($consumed < $reloc_size) {
        my $block_offset = rva_to_file_offset(
            $reloc_rva + $consumed, 8, \@sections, $path
        );
        my ($page_rva, $block_size) = unpack(
            'V2', read_exact($fh, $block_offset, 8, $path)
        );
        $block_size >= 8 && $block_size % 2 == 0 &&
            $consumed + $block_size <= $reloc_size
            or die "Invalid base-relocation block in $path\n";

        my $entry_count = ($block_size - 8) / 2;
        for my $entry_index (0 .. $entry_count - 1) {
            my $entry = unpack(
                'v', read_exact($fh, $block_offset + 8 + $entry_index * 2, 2, $path)
            );
            my $type = $entry >> 12;
            my $offset_in_page = $entry & 0x0fff;
            next if $type == 0; # IMAGE_REL_BASED_ABSOLUTE padding

            my $target_rva = $page_rva + $offset_in_page;
            if ($type == 10) { # IMAGE_REL_BASED_DIR64
                my $target = rva_to_file_offset($target_rva, 8, \@sections, $path);
                my $value = unpack('Q<', read_exact($fh, $target, 8, $path));
                write_exact($fh, $target, pack('Q<', $value + $image_base_delta), $path);
            } elsif ($type == 3) { # IMAGE_REL_BASED_HIGHLOW
                my $target = rva_to_file_offset($target_rva, 4, \@sections, $path);
                my $value = unpack('V', read_exact($fh, $target, 4, $path));
                write_exact(
                    $fh,
                    $target,
                    pack('V', ($value + $image_base_delta) & 0xffffffff),
                    $path
                );
            } else {
                die "Unsupported PE base-relocation type $type in $path\n";
            }
        }
        $consumed += $block_size;
    }
}

my $dll_characteristics_offset = $optional_header + 0x46;
my $dll_characteristics = unpack(
    'v', read_exact($fh, $dll_characteristics_offset, 2, $path)
);

# These flags allow the loader to place DLLs far enough apart that MinGW's
# signed 32-bit runtime pseudo relocations can overflow. Keep all other flags,
# including NX_COMPAT and NO_SEH.
$dll_characteristics &= ~(0x20 | 0x40);

write_exact($fh, $optional_header + 0x18, pack('Q<', $image_base), $path);
write_exact($fh, $dll_characteristics_offset, pack('v', $dll_characteristics), $path);
# The original checksum no longer describes the image. User-mode DLLs may use 0
# to indicate that no PE checksum is supplied.
write_exact($fh, $optional_header + 0x40, pack('V', 0), $path);
close $fh or die "Cannot close $path: $!\n";
