package net.dempsy.vfs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

public abstract class Path {
    protected Vfs vfs = null;

    void setVfs(final Vfs vfs) {
        this.vfs = vfs;
    }

    public abstract URI uri();

    public abstract boolean exists() throws IOException;

    public abstract InputStream read() throws IOException;

    public abstract OutputStream write() throws IOException;

    public abstract boolean delete() throws IOException;

    public abstract long lastModifiedTime() throws IOException;

    public abstract long length() throws IOException;

    /**
     * This should return {@code null} if the {@link Path} isn't a directory/folder.
     * An empty folder will be an array with no elements. A {@link FileNotFoundException}
     * will be thrown if the path doesn't exist at all.
     */
    public abstract Path[] list() throws IOException;

    public boolean isDirectory() throws IOException {
        return list() != null;
    }

    public boolean handlesUngzipping() {
        return false;
    }

    /**
     * Makes the directory (and ancestors if necessary). This method should always throw an exception if the
     * directory doesn't exist when the method completes and NEVER throw an exception if it does.
     *
     * It will throw an {@link UnsupportedOperationException} when the FileSystem implementation doesn't
     * support creating new directories.
     */
    public void mkdirs() throws IOException {
        throw new UnsupportedOperationException("'mkdir' isn't supported for file system implementation " + this.getClass().getSimpleName());
    }

    public File toFile() throws IOException {
        return vfs.toFile(uri());
    }

    /**
     * Helper for allowing implementors to set the vfs on newly create Paths
     */
    protected <T extends Path> T setVfs(final T p) {
        p.setVfs(vfs);
        return p;
    }
}