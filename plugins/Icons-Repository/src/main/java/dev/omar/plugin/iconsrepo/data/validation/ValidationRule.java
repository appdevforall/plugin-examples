package dev.omar.plugin.iconsrepo.data.validation;

public interface ValidationRule {
    
    public boolean isValid(String input);

    public String getErrorMessage();
}
