package dev.omar.plugin.iconsrepo.data.importer;

import com.itsaky.androidide.plugins.extensions.IProject;
import dev.omar.plugin.iconsrepo.data.importer.IconSource;

public class ImportParams {

    public String tint;
    public IProject currentProject;
    public IconSource source;
    public ImportPathResolver dest;

    public ImportParams(IProject currentProject, IconSource source, ImportPathResolver dest) {
        this.currentProject = currentProject;
        this.source = source;
        this.dest = dest;
    }

    public ImportParams useTint(String tint) {
        this.tint = tint;
        return this;
    }

    public boolean isUsedTint() {
        return tint != null;
    }
}
