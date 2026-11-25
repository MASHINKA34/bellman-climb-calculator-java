package ru.mashinka.multiflow.web.admin;

// отказ админки: HTTP-код и текст для человека
public class AdminDeniedException extends RuntimeException {

    private final int status;

    AdminDeniedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
