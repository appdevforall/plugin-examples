package dev.omar.plugin.iconsrepo.data.validation;

import android.view.ViewParent;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.List;

public class Validator {

    public static boolean validate(TextInputEditText editText, List<ValidationRule> rules) {

        if (editText == null) return false;

        String input = "";
        if (editText.getText() != null) {
            input = editText.getText().toString().trim();
        }

        TextInputLayout layout = findTextInputLayout(editText);

        for (ValidationRule rule : rules) {
            if (!rule.isValid(input)) {
                if (layout != null) {
                    layout.setError(rule.getErrorMessage());
                }
                return false;
            }
        }

        if (layout != null) {
            layout.setError(null);
        }

        return true;
    }

    // استخراج TextInputLayout من الأب
    private static TextInputLayout findTextInputLayout(TextInputEditText editText) {
        ViewParent parent = editText.getParent();

        while (parent instanceof android.view.View) {
            if (parent instanceof TextInputLayout) {
                return (TextInputLayout) parent;
            }
            parent = parent.getParent();
        }

        return null;
    }
}