package net.visualstyles.style;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Texture packs the player imported as .zip. Each one is unpacked to config/visualstyles/imported/<id>/full/
 * (pack.png + assets/...) and then shows up in every category it has textures for, like the bundled packs,
 * with its preview built from its own textures. It is also listed under "Whole packs".
 * Zips dropped into config/visualstyles/zips/ are imported automatically.
 */
public final class ImportedPacks {
   private static final long MAX_BYTES = 512L * 1024L * 1024L;
   private static final int MAX_FILES = 60000;
   private static final int SWATCH = 0xFF22D3EE;

   /** Older texture paths (1.12 - 1.21.1) and the 1.21.11 path they become, if the pack lacks the new one. */
   private static final String[][] LEGACY = {
      {"textures/models/armor/netherite_layer_1.png", "textures/entity/equipment/humanoid/netherite.png"},
      {"textures/models/armor/netherite_layer_2.png", "textures/entity/equipment/humanoid_leggings/netherite.png"},
      {"textures/models/armor/diamond_layer_1.png", "textures/entity/equipment/humanoid/diamond.png"},
      {"textures/models/armor/diamond_layer_2.png", "textures/entity/equipment/humanoid_leggings/diamond.png"},
      {"textures/items/totem.png", "textures/item/totem_of_undying.png"},
      {"textures/item/totem.png", "textures/item/totem_of_undying.png"},
      {"textures/blocks/fire_layer_0.png", "textures/block/fire_0.png"},
      {"textures/blocks/fire_layer_1.png", "textures/block/fire_1.png"},
      {"textures/block/fire_layer_0.png", "textures/block/fire_0.png"},
      {"textures/block/fire_layer_1.png", "textures/block/fire_1.png"},
      {"textures/entity/endercrystal/endercrystal.png", "textures/entity/end_crystal/end_crystal.png"},
      {"textures/misc/enchanted_item_glint.png", "textures/misc/enchanted_glint_item.png"},
      {"textures/misc/enchanted_item_glint.png", "textures/misc/enchanted_glint_armor.png"},
      {"textures/misc/enchanted_glint_entity.png", "textures/misc/enchanted_glint_armor.png"},
      {"textures/items/ender_pearl.png", "textures/item/ender_pearl.png"},
      {"textures/items/diamond_sword.png", "textures/item/diamond_sword.png"},
      {"textures/items/diamond_pickaxe.png", "textures/item/diamond_pickaxe.png"},
      {"textures/items/diamond_axe.png", "textures/item/diamond_axe.png"},
      {"textures/items/diamond_helmet.png", "textures/item/diamond_helmet.png"},
      {"textures/items/diamond_chestplate.png", "textures/item/diamond_chestplate.png"},
      {"textures/items/diamond_leggings.png", "textures/item/diamond_leggings.png"},
      {"textures/items/diamond_boots.png", "textures/item/diamond_boots.png"},
      {"textures/blocks/glowstone.png", "textures/block/glowstone.png"},
      {"textures/blocks/obsidian.png", "textures/block/obsidian.png"},
      {"textures/blocks/stone.png", "textures/block/stone.png"},
      {"textures/blocks/snow.png", "textures/block/snow.png"},
   };

   private record Pack(String id, String name, Path full) {
      Path tex(String path) {
         return this.full.resolve("assets").resolve("minecraft").resolve(path);
      }
   }

   private static List<Pack> packs;
   private static Map<Coll, List<Style>> byColl;
   /** Zips (by signature) being imported right now or that failed, so the drop folder does not retry them. */
   private static final Set<String> SKIP = java.util.concurrent.ConcurrentHashMap.newKeySet();
   /** Set by a background import, picked up by the menu. */
   private static volatile String finished;

   private ImportedPacks() {
   }

   public static Path root() {
      return FabricLoader.getInstance().getConfigDir().resolve("visualstyles").resolve("imported");
   }

   /** Drop folder: every .zip in here is imported when the menu is open. */
   public static Path inbox() {
      return FabricLoader.getInstance().getConfigDir().resolve("visualstyles").resolve("zips");
   }

   public static void refresh() {
      packs = null;
      byColl = null;
   }

   public static List<Style> of(Coll c) {
      if (byColl == null) {
         load();
      }
      return byColl.getOrDefault(c, List.of());
   }

   /** Unpacked files of a whole imported pack (for "Whole packs"); null if there is no such pack. */
   public static Path fullDir(String id) {
      if (packs == null) {
         load();
      }
      for (Pack p : packs) {
         if (p.id.equals(id)) {
            return p.full;
         }
      }
      return null;
   }

   /** A message from an import that finished in the background, once; else null. */
   public static String takeFinished() {
      String m = finished;
      finished = null;
      return m;
   }

   // ------------------------------------------------------------------ listing

   private static void load() {
      List<Pack> list = new ArrayList<>();
      Path root = root();
      if (Files.isDirectory(root)) {
         try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.sorted().toList()) {
               String id = d.getFileName().toString();
               Path full = d.resolve("full");
               if (id.endsWith(".tmp") || !Files.isDirectory(full)) {
                  continue;
               }
               String name = id;
               try {
                  Path n = d.resolve("name.txt");
                  if (Files.isRegularFile(n)) {
                     name = Files.readString(n, StandardCharsets.UTF_8).trim();
                  }
               } catch (IOException ignored) {
               }
               list.add(new Pack(id, name.isEmpty() ? id : name, full));
            }
         } catch (IOException ignored) {
         }
      }
      Map<Coll, List<Style>> out = new EnumMap<>(Coll.class);
      for (Coll c : Coll.values()) {
         List<Style> styles = new ArrayList<>();
         for (Pack p : list) {
            if (c == Coll.PACKS) {
               styles.add(new Style("zipfull:" + p.id, p.name, SWATCH, (path, van) -> {
                  Path png = p.full.resolve("pack.png");
                  return "pack.png".equals(path) && Files.isRegularFile(png) ? Pic.read(png) : null;
               }, null, path -> null));
               continue;
            }
            Set<String> has = new LinkedHashSet<>();
            for (String t : c.targets) {
               if (Files.isRegularFile(p.tex(t))) {
                  has.add(t);
               }
            }
            if (has.isEmpty()) {
               continue;
            }
            styles.add(new Style("zip:" + p.id, p.name, SWATCH, (path, van) -> has.contains(path) ? Pic.read(p.tex(path)) : null,
               null, path -> {
                  try {
                     Path meta = p.tex(path + ".mcmeta");
                     return Files.isRegularFile(meta) ? Files.readAllBytes(meta) : null;
                  } catch (IOException e) {
                     return null;
                  }
               }));
         }
         out.put(c, List.copyOf(styles));
      }
      packs = list;
      byColl = out;
   }

   // ------------------------------------------------------------------ importing

   /** Imports in a background thread; the result shows up through {@link #takeFinished()}. */
   public static void importAsync(List<Path> zips) {
      Thread t = new Thread(() -> {
         List<String> msgs = new ArrayList<>();
         for (Path z : zips) {
            msgs.add(importZip(z));
         }
         finished = String.join("  ·  ", msgs);
      }, "Visual Styles zip import");
      t.setDaemon(true);
      t.start();
   }

   /** Imports every zip in the drop folder that was not imported yet. Returns true if one was started. */
   public static boolean importInbox() {
      Path in = inbox();
      if (!Files.isDirectory(in)) {
         return false;
      }
      List<Path> todo = new ArrayList<>();
      try (Stream<Path> files = Files.list(in)) {
         for (Path z : files.sorted().toList()) {
            if (isZip(z) && !alreadyImported(z)) {
               try {
                  if (SKIP.add(signature(z))) {
                     todo.add(z);
                  }
               } catch (IOException ignored) {
               }
            }
         }
      } catch (IOException ignored) {
      }
      if (todo.isEmpty()) {
         return false;
      }
      importAsync(todo);
      return true;
   }

   public static boolean isZip(Path p) {
      return Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
   }

   private static String signature(Path zip) throws IOException {
      return zip.getFileName() + "|" + Files.size(zip) + "|" + Files.getLastModifiedTime(zip).toMillis();
   }

   private static boolean alreadyImported(Path zip) {
      try {
         String sig = signature(zip);
         Path root = root();
         if (!Files.isDirectory(root)) {
            return false;
         }
         try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.toList()) {
               Path s = d.resolve("source.txt");
               if (Files.isRegularFile(s) && sig.equals(Files.readString(s, StandardCharsets.UTF_8).trim())) {
                  return true;
               }
            }
         }
      } catch (IOException ignored) {
      }
      return false;
   }

   /** Unpacks one zip. Returns a short message for the menu. */
   public static String importZip(Path zip) {
      String name = packName(zip.getFileName().toString());
      String id = slug(name);
      Path root = root();
      Path tmp = root.resolve(id + ".tmp");
      try {
         Files.createDirectories(root);
         deleteTree(tmp);
         Path full = tmp.resolve("full");
         Files.createDirectories(full);
         int files = 0;
         long bytes = 0L;
         try (ZipFile zf = new ZipFile(zip.toFile())) {
            String prefix = prefix(zf);
            if (prefix == null) {
               deleteTree(tmp);
               return name + ": no texture pack inside (no assets folder)";
            }
            Path base = full.normalize();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
               ZipEntry e = en.nextElement();
               String n = e.getName().replace('\\', '/');
               if (e.isDirectory() || !n.startsWith(prefix)) {
                  continue;
               }
               String rel = n.substring(prefix.length());
               if (!rel.equals("pack.png") && !rel.equals("pack.mcmeta") && !rel.startsWith("assets/")) {
                  continue;
               }
               Path target = base.resolve(rel).normalize();
               if (!target.startsWith(base) || rel.contains("..")) {
                  continue;
               }
               if (++files > MAX_FILES) {
                  throw new IOException("too many files");
               }
               Files.createDirectories(target.getParent());
               try (InputStream in = zf.getInputStream(e); OutputStream out = Files.newOutputStream(target)) {
                  byte[] buf = new byte[65536];
                  int r;
                  while ((r = in.read(buf)) > 0) {
                     bytes += r;
                     if (bytes > MAX_BYTES) {
                        throw new IOException("pack is larger than 512 MB");
                     }
                     out.write(buf, 0, r);
                  }
               }
            }
         }
         // old packs: copy textures to their 1.21.11 paths
         Path mc = full.resolve("assets").resolve("minecraft");
         for (String[] m : LEGACY) {
            Path from = mc.resolve(m[0]);
            Path to = mc.resolve(m[1]);
            if (Files.isRegularFile(from) && !Files.exists(to)) {
               Files.createDirectories(to.getParent());
               Files.copy(from, to);
               Path meta = from.resolveSibling(from.getFileName() + ".mcmeta");
               if (Files.isRegularFile(meta)) {
                  Files.copy(meta, to.resolveSibling(to.getFileName() + ".mcmeta"), StandardCopyOption.REPLACE_EXISTING);
               }
            }
         }
         Files.writeString(tmp.resolve("name.txt"), name, StandardCharsets.UTF_8);
         Files.writeString(tmp.resolve("source.txt"), signature(zip), StandardCharsets.UTF_8);
         List<String> cats = categories(mc);
         Path dest = root.resolve(id);
         deleteTree(dest);
         Files.move(tmp, dest);
         if (cats.isEmpty()) {
            return name + " imported (only under Whole packs: no textures of the other categories)";
         }
         return name + " imported: " + String.join(", ", cats);
      } catch (Exception e) {
         try {
            deleteTree(tmp);
         } catch (IOException ignored) {
         }
         return name + ": could not import (" + e.getMessage() + ")";
      }
   }

   private static List<String> categories(Path mc) {
      List<String> out = new ArrayList<>();
      for (Coll c : Coll.values()) {
         for (String t : c.targets) {
            if (c != Coll.PACKS && Files.isRegularFile(mc.resolve(t))) {
               out.add(c.title);
               break;
            }
         }
      }
      return out;
   }

   /** The folder inside the zip that holds assets/ (packs are often zipped with their folder). */
   private static String prefix(ZipFile zf) {
      String best = null;
      Enumeration<? extends ZipEntry> en = zf.entries();
      while (en.hasMoreElements()) {
         String n = en.nextElement().getName().replace('\\', '/');
         int i = n.startsWith("assets/") ? 0 : n.indexOf("/assets/") + 1;
         if (i == 0 && !n.startsWith("assets/")) {
            continue;
         }
         String p = n.substring(0, i);
         if (p.startsWith("__MACOSX")) {
            continue;
         }
         if (best == null || p.length() < best.length()) {
            best = p;
         }
      }
      return best;
   }

   /** "! §dFaith Vanilla.zip" -> "Faith Vanilla" */
   static String packName(String file) {
      String n = file;
      if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) {
         n = n.substring(0, n.length() - 4);
      }
      n = n.replaceAll("§.", "").replace('_', ' ').trim();
      n = n.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
      return n.isEmpty() ? "Imported pack" : n;
   }

   static String slug(String name) {
      String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
      if (s.isEmpty()) {
         s = "pack";
      }
      return s.length() > 48 ? s.substring(0, 48) : s;
   }

   /** Removes an imported pack. */
   public static boolean delete(String id) {
      try {
         Path d = root().resolve(id).normalize();
         if (!d.startsWith(root().normalize()) || d.equals(root().normalize())) {
            return false;
         }
         deleteTree(d);
         refresh();
         return true;
      } catch (IOException e) {
         return false;
      }
   }

   private static void deleteTree(Path dir) throws IOException {
      if (!Files.exists(dir)) {
         return;
      }
      List<Path> all = new ArrayList<>();
      try (Stream<Path> walk = Files.walk(dir)) {
         walk.forEach(all::add);
      }
      all.sort(Comparator.reverseOrder());
      for (Path p : all) {
         Files.deleteIfExists(p);
      }
   }
}
