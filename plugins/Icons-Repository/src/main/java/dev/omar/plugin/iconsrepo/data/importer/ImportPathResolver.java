package dev.omar.plugin.iconsrepo.data.importer;
import com.itsaky.androidide.plugins.extensions.IProject;
import java.io.File;

public interface ImportPathResolver {
    
	File getDrawableDirectory(IProject project);
}