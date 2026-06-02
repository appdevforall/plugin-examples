package dev.omar.plugin.iconsrepo.data.importer;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public class ImportIconResult {
    private boolean isSuccess;
    private String message;

    public ImportIconResult(boolean isSucces) {
        this(isSucces,null);
    }

    public ImportIconResult(boolean isSucces, String message) {
        this.isSuccess = isSucces;
        this.message = message;
    }

    public boolean isSuccess() {
        return isSuccess;
    }

    public String getMessage() {
        return message;
    }

    @NonNull
    @Contract(value = " -> new", pure = true)
    public static ImportIconResult success(){
        return new ImportIconResult(true);
    }

    @NonNull
    @Contract(value = "_ -> new", pure = true)
    public static ImportIconResult error(String error){
        return new ImportIconResult(false,error);
    }

}
