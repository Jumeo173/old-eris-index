package com.example.olderisindex.storage;

public class StorageConfigException extends RuntimeException {
    public StorageConfigException(String msg) { super(msg); }
    public StorageConfigException(String msg, Throwable cause) { super(msg, cause); }
}
