package dev.omar.plugin.iconsrepo.data.validation;

public class HexColorRule implements ValidationRule {
    private static final String PATTERN = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$";
    @Override
    public boolean isValid(String input) {
        if (input == null) {
            return false;
        }
        input = input.trim();

        return input.matches(PATTERN);
    }

    @Override
    public String getErrorMessage() {
        return "Invalid hex color Use #RRGGBB or AARRGGBB format.";
    }
}
