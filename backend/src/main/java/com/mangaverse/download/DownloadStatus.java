package com.mangaverse.download;

import java.util.EnumSet;
import java.util.Set;

public enum DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED;

    public static final Set<DownloadStatus> ACTIVE = EnumSet.of(QUEUED, DOWNLOADING, PAUSED);
}
