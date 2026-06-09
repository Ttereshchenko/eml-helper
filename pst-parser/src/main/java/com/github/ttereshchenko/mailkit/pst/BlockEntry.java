package com.github.ttereshchenko.mailkit.pst;

public record BlockEntry(long blockId, long offset, int size, int refCount, int inflatedSize) {}
