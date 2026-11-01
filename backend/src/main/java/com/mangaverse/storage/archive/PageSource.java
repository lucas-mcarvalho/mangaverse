package com.mangaverse.storage.archive;

import java.io.IOException;
import java.util.List;

/** Acesso página a página a um capítulo local, sem extração permanente. */
public interface PageSource extends AutoCloseable {

    List<String> pageNames();

    PageData read(int index) throws IOException;

    @Override
    void close() throws IOException;
}
