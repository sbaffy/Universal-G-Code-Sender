/*
    Copyright 2020 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.ugs.nbp;

import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;
import org.openide.util.Exceptions;

/**
 *
 * @author will
 */
public class S3FileSystemView extends FileSystemView {
    // Must match "s3://<bucket>" or "s3://<bucket>/"
    // Must match "s3:/<bucket>"  or "s3:/<bucket>/"
    final static Pattern parseURI = Pattern.compile("^s3://?(?<bucket>[^/]+)/?(?<path>.*)$");
    private static boolean hasBucket(File f) {
        return parseURI(f).matches();
    }
    
    private static Matcher parseURI(File f) {
        String path = f.toString();
        return parseURI.matcher(path);
    }
    
    final String id;
    final String secret;
    final MinioClient minioClient;
            
    public S3FileSystemView(final String id, final String secret) throws Exception {
        this.id = id;
        this.secret = secret;
        this.minioClient = new MinioClient("https://s3.amazonaws.com", id, secret);
        
        // Make sure it works with a simple call.
        this.minioClient.listBuckets();
    }
	
    @Override
    protected File createFileSystemRoot(File f) {
        return new VirtualFile(f, 1);
    }

    @Override
    public boolean isComputerNode(File dir) {
        return false;
    }

    @Override
    public boolean isFloppyDrive(File dir) {
        return false;
    }

    @Override
    public boolean isDrive(File dir) {
        return false;
    }

    @Override
    public Icon getSystemIcon(File f) {
        return null;
    }

    @Override
    public String getSystemTypeDescription(File f) {
        return f.toPath().toString();
    }

    @Override
    public String getSystemDisplayName(File f) {
        return f.getName();
    }

    @Override
    public File getParentDirectory(final File dir) {
        if (dir == null) return getDefaultDirectory();
        return dir.getParentFile();
    }

    @Override
    public File[] getFiles(final File dir, boolean useFileHiding) {
        // If there is no bucket (i.e. just "s3:/"), add the buckets.
        if (!hasBucket(dir)) {
            try {
                final List<File> files = new ArrayList<>(1);

                List<Bucket> bucketList = minioClient.listBuckets();
                for (Bucket bucket : bucketList) {
                    System.out.println(bucket.creationDate() + ", " + bucket.name());
                    files.add(new VirtualFile("s3:/" + bucket.name() + "/", 0));
                }

                return files.toArray(new File[files.size()]);
            } catch (Exception ex) {
                Exceptions.printStackTrace(ex);
            }

            return new VirtualFile[0];
        }
                
        ArrayList<VirtualFile> ret = new ArrayList<>();
        Matcher m = parseURI(dir);
        
        try {
            if (m.matches()) {
                String bucket = m.group("bucket");
                // Path should start with a slash unless it's empty
                String path = m.group("path");
                if (! "".equals(path)) {
                    path += "/";
                }

                Iterable<Result<Item>> objects = minioClient.listObjects(bucket, path, false);

                String prefix = "s3:/" + bucket + "/";
                String dirMatch = dir.toString() + "/";
                for (Result<Item> res : objects) {
                    Item i = res.get();
                    String name = prefix + i.objectName();

                    // listObjects matches the current directory, filter it out.
                    if (name.equals(dirMatch)) {
                        continue;
                    }
                    VirtualFile f = new VirtualFile(name, i.objectSize());
                    if (!f.isDir) {
                        f.setLastModified(i.lastModified().getTime());
                    }
                    ret.add(f);
                }
            }
        } catch (Exception ex) {
            Exceptions.printStackTrace(ex);
        }
        
        return ret.toArray(new VirtualFile[0]);
    }

    @Override
    public File createFileObject(final String path) {
        return new VirtualFile(path, 1);
    }

    @Override
    public File createFileObject(final File dir, final String filename) {
        throw new UnsupportedOperationException("Sorry, no support for creating files in S3.");
        
        /*
        Path fileObject;

        if (dir != null) {
            fileObject = Paths.get(dir.toPath().toString(), filename);
        } else {
            fileObject = Paths.get(filename);
        }
        
        return new VirtualFile(fileObject.toFile(), 1);
        */
    }

    @Override
    public File getDefaultDirectory() {
        return new VirtualFile("s3:/", 1);
    }

    @Override
    public File getHomeDirectory() {
        return getDefaultDirectory();
    }

    @Override
    public File[] getRoots() {
        final List<File> files = new ArrayList<>(1);
        files.add(new VirtualFile("s3:/", 1));
        return files.toArray(new VirtualFile[0]);
    }

    @Override
    public boolean isFileSystemRoot(final File dir) {
        return !hasBucket(dir);
    }

    @Override
    public boolean isHiddenFile(final File f) {
        return false;
    }

    @Override
    public boolean isFileSystem(final File f) {
        return !isFileSystemRoot(f);
    }

    @Override
    public File getChild(final File parent, final String fileName) {
        throw new UnsupportedOperationException("Not sure when this would make sense. Call getFiles instead.");
    }

    @Override
    public boolean isParent(final File folder, final File file) {
        return file.toPath().getParent().equals(folder.toPath());
    }

    @Override
    public Boolean isTraversable(final File f) {
        return f.isDirectory();
    }

    @Override
    public boolean isRoot(final File f) {
        // Root should just be "s3:/" or "s3:", so check that there is no bucket.
        boolean hasBucket = hasBucket(f);
        return hasBucket == false;
    }

    @Override
    public File createNewFolder(final File containingDir) throws IOException {
        throw new UnsupportedOperationException("Sorry, no support for editing S3.");
    }


    private class VirtualFile extends File {

        private static final long serialVersionUID = -1752685357864733168L;
        private final boolean isDir;
        private final long length;
        private long lastModified = 0;

        private VirtualFile(final File file, long length) {
            this(file.toString(), length);
        }

        private VirtualFile(String pathname, long length) {
            super(pathname);
            isDir = pathname.endsWith("/");
            this.length = length;
        }

        private VirtualFile(String parent, String child, long length) {
            super(parent, child);
            isDir = child.endsWith("/");
            this.length = length;
        }

        private VirtualFile(File parent, String child, long length) {
            super(parent, child);
            isDir = child.endsWith("/");
            this.length = length;
        }
        
        @Override
        public boolean setLastModified(long t) {
            this.lastModified = t;
            return true;
        }
        
        @Override
        public long lastModified() {
            return lastModified;
        }

        @Override
        public long length() {
            return length;
        }
        
        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public boolean isDirectory() {
            return isDir;
        }

        @Override
        public File getCanonicalFile() throws IOException {
            return this;
        }

        @Override
        public File getAbsoluteFile() {
            return this;
        }

        @Override
        public File getParentFile() {
            final int lastIndex = this.toString().lastIndexOf('/');
            
            if (lastIndex == -1) return null;
            
            String parent = this.toString().substring(0, lastIndex + 1);

            return new VirtualFile(parent, 1);
        }

    }

}