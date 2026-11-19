package dev.starcore.starcore.core.extension;

public interface ExtensionPoint<T, R> { String getName(); T execute(R request); }
