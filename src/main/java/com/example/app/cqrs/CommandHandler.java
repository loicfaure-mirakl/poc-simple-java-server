package com.example.app.cqrs;

public interface CommandHandler<C, R> {

    R handle(C command);
}
