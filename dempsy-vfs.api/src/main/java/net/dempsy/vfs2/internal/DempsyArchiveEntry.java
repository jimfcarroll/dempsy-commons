package net.dempsy.vfs2.internal;

import java.io.File;

import org.apache.commons.compress.archivers.ArchiveEntry;

public interface DempsyArchiveEntry extends ArchiveEntry {

    File direct();

}
