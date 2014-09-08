# DirDiff: create diffs, binary patch files, and apply them to whole directories

The theory here is pretty simple: we want something like the rsync algorithm,
and its efficient way of differencing and patching files if large runs of those
files are similar.

The trouble is, we aren't so much interested in syncing individual, named files
all the time, but rather a collection of files within a named directory (use case:
various database systems that write various files to a fixed set of directories,
but with files with names that vary with usage). We kind of want to just work with
the `cat` of all files in a directory, but that doesn't really work.

So here's the idea:

1. When generating a checksum manifest, we do it on an entire directory. This implies
that this manifest keeps:
  1. A list of file names, file attributes, and references to hashed data blocks
  that compose the file.
  2. A big list of block hash info, across all files in the directory. We want that
  because we want to track similar data across files.
2. When generating a patch, we scan the entire block space across all files, but we
also include the new file information (which might include new file names that were
not in the original, but with similar contents, and file names that have been removed).