package com.mineportal.server.servers;

public class DuplicateServerException extends RuntimeException {
    public DuplicateServerException() {
        super("이미 등록된 서버 주소예요");
    }
}
