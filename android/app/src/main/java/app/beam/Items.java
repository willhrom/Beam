package app.beam;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Turns picked or shared content into transfer items (works out names and exact sizes). */
final class Items {
  interface Done { void run(List<Transfer.Item> items, String error); }

  /** Everything inside a folder picked with the system folder picker, each file tagged with its path inside it. */
  static void buildFolder(Context c, Uri tree, Done done) {
    final Beam beam = Beam.get(c);
    new Thread(() -> {
      List<Transfer.Item> out = new ArrayList<>();
      String err = null;
      try {
        ContentResolver cr = c.getContentResolver();
        String rootDoc = android.provider.DocumentsContract.getTreeDocumentId(tree);
        String rootName = null;
        try (Cursor cur = cr.query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootDoc),
            new String[] { android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
          if (cur != null && cur.moveToFirst()) rootName = cur.getString(0);
        }
        if (rootName == null || rootName.isEmpty()) rootName = "Folder";
        walk(cr, tree, rootDoc, Transfer.cleanName(rootName), out);
      } catch (Exception e) {
        err = e.getMessage() != null ? e.getMessage() : "Couldn't read the folder";
      }
      final String e2 = err;
      beam.main.post(() -> done.run(e2 == null ? out : null, e2));
    }, "beam-folder").start();
  }

  private static void walk(ContentResolver cr, Uri tree, String docId, String path, List<Transfer.Item> out) {
    if (out.size() >= 5000) return;
    Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
    String[] cols = { android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE };
    try (Cursor cur = cr.query(children, cols, null, null, null)) {
      while (cur != null && cur.moveToNext()) {
        String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
        long size = cur.isNull(3) ? -1 : cur.getLong(3);
        if (name == null || name.startsWith(".")) continue;
        if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
          walk(cr, tree, id, path + "/" + Transfer.cleanName(name), out);
        } else {
          Transfer.Item it = new Transfer.Item(Transfer.cleanName(name), Math.max(0, size), mime != null ? mime : "application/octet-stream", false);
          it.uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id);
          it.path = path;
          out.add(it);
          if (out.size() >= 5000) return;
        }
      }
    }
  }

  /**
   * @param copy make a private copy of each file first. Needed for the share sheet, because the
   *             permission to read shared files ends when the share screen closes.
   */
  static void build(Context c, List<Uri> uris, String text, boolean copy, Done done) {
    final Beam beam = Beam.get(c);
    new Thread(() -> {
      List<Transfer.Item> out = new ArrayList<>();
      String err = null;
      try {
        ContentResolver cr = c.getContentResolver();
        File dir = new File(c.getCacheDir(), "out");
        dir.mkdirs();
        for (Uri u : uris) {
          String name = null;
          long size = -1;
          try (Cursor cur = cr.query(u, new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
              if (!cur.isNull(0)) name = cur.getString(0);
              if (!cur.isNull(1)) size = cur.getLong(1);
            }
          } catch (Exception ignored) {}
          if (name == null) name = u.getLastPathSegment();
          String type = cr.getType(u);
          if (type == null) type = "application/octet-stream";
          Transfer.Item it;
          if (copy || size < 0) {
            File f = new File(dir, Crypto.randomId());
            try (InputStream in = cr.openInputStream(u); OutputStream o = new FileOutputStream(f)) {
              if (in == null) throw new Exception("Couldn't read " + name);
              byte[] buf = new byte[256 * 1024];
              int n;
              while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            it = new Transfer.Item(Transfer.cleanName(name), f.length(), type, false);
            it.temp = f;
          } else {
            it = new Transfer.Item(Transfer.cleanName(name), size, type, false);
            it.uri = u;
          }
          out.add(it);
        }
        if (text != null && !text.trim().isEmpty()) {
          Transfer.Item t = new Transfer.Item("Text", text.getBytes(StandardCharsets.UTF_8).length, "text/plain", true);
          t.textValue = text;
          out.add(t);
        }
      } catch (Exception e) {
        err = e.getMessage() != null ? e.getMessage() : "Couldn't read the files";
        for (Transfer.Item i : out) if (i.temp != null) i.temp.delete();
      }
      final String e2 = err;
      beam.main.post(() -> done.run(e2 == null ? out : null, e2));
    }, "beam-items").start();
  }
}
