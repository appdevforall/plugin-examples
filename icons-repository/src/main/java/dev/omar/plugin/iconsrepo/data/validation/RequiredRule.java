package dev.omar.plugin.iconsrepo.data.validation;

public class RequiredRule implements ValidationRule {

    @Override
    public boolean isValid(String input) {
        return input != null && !input.trim().isEmpty();
    }

    @Override
    public String getErrorMessage() {
        return "Required!";
    }
}