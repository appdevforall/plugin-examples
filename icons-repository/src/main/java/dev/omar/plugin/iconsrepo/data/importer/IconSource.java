package dev.omar.plugin.iconsrepo.data.importer;

import java.io.IOException;
import java.io.InputStream;

public interface IconSource {

    String getName();

    InputStream getStream() throws IOException;
}
