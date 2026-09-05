package dev.omar.plugin.iconsrepo.data.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.itsaky.androidide.plugins.extensions.IModule;
import com.itsaky.androidide.plugins.extensions.ModuleType;
import com.itsaky.androidide.plugins.extensions.SourceSet;
import com.itsaky.androidide.plugins.extensions.ProjectType;
import com.itsaky.androidide.plugins.extensions.IProject;
import java.io.File;
import dev.omar.plugin.iconsrepo.data.importer.ImportPathResolver;
import java.util.Collections;
import java.util.List;

public class AppPathResolver implements ImportPathResolver {

    @Override
    public File getDrawableDirectory(IProject project) {
        File file = new File(getDrawablePath(project));
        if(!file.exists() && file.canWrite()){
            file.mkdirs();
        }
        return file;
    }

    @NonNull
    private String getDrawablePath(IProject project) {
        return new File(getResDir(project), "drawable").getAbsolutePath();
    }

    @Nullable
    private IModule getAppModule(@NonNull IProject project) {
        if (project.getType() == ProjectType.GRADLE_PLUGIN) {
            return new IModule() {
                @NonNull
                @Override
                public String getName() {
                    return project.getName();
                }

                @NonNull
                @Override
                public ModuleType getType() {
                    return ModuleType.ANDROID_APP;
                }

                @NonNull
                @Override
                public File getProjectDir() {
                    return project.getRootDir();
                }

                @NonNull
                @Override
                public List<SourceSet> getSourceSets() {
                    File main = new File(getProjectDir(), "src/main");
                    return Collections.singletonList(
                            new SourceSet(
                                    main.getName(),
                                    List.of(new File(main, "java")),
                                    List.of(new File(main, "res"))));
                }
            };
        } else {
            for (IModule module : project.getModules()) {
                if (module.getName().equals("app")) {
                    return module;
                }
            }
        }

        return null;
    }

    @Nullable
    private SourceSet getMainSourceSet(@NonNull IModule module) {
        for (SourceSet source : module.getSourceSets()) {
            if (source.getName().equals("main")) {
                return source;
            }
        }
        return null;
    }

    @Nullable
    private File getResDir(IProject project) {
        SourceSet source = getMainSourceSet(getAppModule(project));
        for (File file : source.getResourceDirs()) {
            if (file.getName().equals("res")) {
                return file;
            }
        }
        return null;
    }
}
