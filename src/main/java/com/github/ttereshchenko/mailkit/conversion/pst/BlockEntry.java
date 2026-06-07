package com.github.ttereshchenko.mailkit.conversion.pst;

public record BlockEntry(long blockId, long offset, int size, int refCount, int inflatedSize) {}
