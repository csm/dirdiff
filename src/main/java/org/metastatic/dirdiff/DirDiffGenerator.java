package org.metastatic.dirdiff;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;
import org.metastatic.rsync.*;
import org.metastatic.sexp4j.Atom;
import org.metastatic.sexp4j.CanonicalWriter;
import static org.metastatic.sexp4j.ExpressionBuilder.create;

import static org.metastatic.sexp4j.Primitives.bytes;

public class DirDiffGenerator
{
    private final Configuration configuration;
    private final Path path;
    private final OutputStream out;

    public DirDiffGenerator(Configuration configuration, Path path, OutputStream out)
    {
        Preconditions.checkNotNull(configuration);
        Preconditions.checkNotNull(path);
        Preconditions.checkArgument(Files.isDirectory(path));
        Preconditions.checkNotNull(out);
        this.configuration = configuration;
        this.path = path;
        this.out = out;
    }

    public void generate() throws IOException, NoSuchAlgorithmException {
        CanonicalWriter writer = new CanonicalWriter(this.out);
        writer.beginList();
        // (sums
        //    (block-length <len>)
        //    (strong-sum <alg-name>)
        //    ( <list of entries...> ))
        writer.writeAtom(new Atom(bytes("sums")));
        writer.writeExpression(create().beginList().atom("block-length")
                .atom(bytes(configuration.blockLength)).build());
        writer.writeExpression(create().beginList().atom("strong-sum")
                .atom(configuration.strongSum.getAlgorithm()).build());
        writer.beginList();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.path)) {
            for (Path path : stream) {
                PosixFileAttributes attrib = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (Files.isDirectory(path))
                    continue;
                if (Files.isSymbolicLink(path))
                {
                    // (link
                    //    (name <name>)
                    //    (owner <user>)
                    //    (group <group>)
                    //    (perm <perms>)
                    //    (ctime <ctime>)
                    //    (mtime <mtime>)
                    //    (target <target>))
                    writer.beginList();
                    writer.writeAtom(new Atom(bytes("link")));
                    writeAttributes(writer, path, attrib);
                    writer.writeExpression(create().beginList().atom("target")
                            .atom(Files.readSymbolicLink(path).toFile().getPath()).build());
                    writer.endList();
                }
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                {
                    // (file
                    //    (name <name>)
                    //    (owner <user>)
                    //    (group <group>)
                    //    (perm <perms>)
                    //    (ctime <ctime>)
                    //    (mtime <mtime>)
                    //    (length <length>)
                    //    (hashes
                    //       (((offset <offset>)
                    //         (length <length>)
                    //         (weak <weaksum>)
                    //         (strong <strongsum>))
                    //         ...)
                    //    (<hash> <hash-value>))
                    writer.beginList();
                    writer.writeAtom(new Atom(bytes("file")));
                    writeAttributes(writer, path, attrib);
                    writer.beginList();
                    writer.writeAtom(new Atom(bytes("hashes")));
                    writer.beginList();
                    MessageDigest sha256 = MessageDigest.getInstance("SHA256");
                    DigestInputStream din = new DigestInputStream(Files.newInputStream(path), sha256);
                    GeneratorStream gen = new GeneratorStream(configuration);
                    gen.addListener(generatorEvent -> {
                        try {
                            writer.beginList();
                            writer.writeExpression(create().beginList().atom("offset")
                                    .atom(bytes(generatorEvent.getChecksumLocation().getOffset())).build());
                            writer.writeExpression(create().beginList().atom("length")
                                    .atom(bytes(generatorEvent.getChecksumLocation().getLength())).build());
                            writer.writeExpression(create().beginList().atom("weak")
                                    .atom(bytes(generatorEvent.getChecksumLocation().getChecksumPair().getWeak())).build());
                            writer.writeExpression(create().beginList().atom("strong")
                                    .atom(generatorEvent.getChecksumLocation().getChecksumPair().getStrong()).build());
                            writer.endList();
                        } catch (IOException e) {
                            throw new ListenerException(e);
                        }
                    });
                    byte[] buf = new byte[4096];
                    int read;
                    while ((read = din.read(buf)) != -1) {
                        try {
                            gen.update(buf, 0, read);
                        } catch (ListenerException le) {
                            if (le.getCause() instanceof IOException)
                                throw (IOException) le.getCause();
                            throw new IOException(le);
                        }
                    }
                    writer.endList();
                    writer.writeExpression(create().beginList().atom("sha256")
                            .atom(sha256.digest()).build());
                    writer.endList();
                }
            }
        }
        writer.endList();
        writer.endList();
    }

    private void writeAttributes(CanonicalWriter writer, Path path, PosixFileAttributes attrib) throws IOException {
        writer.writeExpression(create().beginList().atom("name")
                .atom(path.getFileName().toString()).build());
        writer.writeExpression(create().beginList().atom("owner")
                .atom(attrib.owner().getName()).build());
        writer.writeExpression(create().beginList().atom("group")
                .atom(attrib.group().getName()).build());
        writer.writeExpression(create().beginList().atom("perm")
                .atom(PosixFilePermissions.toString(attrib.permissions())).build());
        writer.writeExpression(create().beginList().atom("ctime")
                .atom(bytes(attrib.creationTime().to(TimeUnit.SECONDS))).build());
        writer.writeExpression(create().beginList().atom("mtime")
                .atom(bytes(attrib.lastModifiedTime().to(TimeUnit.SECONDS))).build());
    }
}
