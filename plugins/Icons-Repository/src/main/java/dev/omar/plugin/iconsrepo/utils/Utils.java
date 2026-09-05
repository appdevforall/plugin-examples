package dev.omar.plugin.iconsrepo.utils;
import android.content.ClipboardManager;
import android.content.Context;

public final class Utils {
    
	private Utils() {
		
	}
    public static CharSequence getClipboardText(Context context) {
    	ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if(cm.hasPrimaryClip()){
            return cm.getPrimaryClip().getItemAt(0).getText();
        }
        return null;
    }


}