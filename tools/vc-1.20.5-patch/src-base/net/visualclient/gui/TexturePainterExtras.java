package net.visualclient.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_315;
import net.minecraft.class_3283;
import net.minecraft.class_3288;
import net.minecraft.class_3298;
import net.minecraft.class_3300;

public final class TexturePainterExtras {
   public static final String PACK_NAME = "Visual Client Texture Edits";
   private static final String PLACED_CRYSTAL = "placed_end_crystal";
   private static final String DESCRIPTION = "Textures painted with Visual Client";
   private static final String[] PREFERRED_TEXTURE_KEYS = new String[]{
      "layer0", "all", "side", "texture", "top", "front", "end", "cross", "plant", "pattern", "particle"
   };
   private static final AtomicBoolean EXPORTING = new AtomicBoolean(false);
   private static int[] packFormat;

   private TexturePainterExtras() {
   }

   public static String[] extraEntry(String var0, String var1) {
      return "minecraft".equals(var0) && "end_crystal".equals(var1)
         ? new String[]{"minecraft:placed_end_crystal", "textures/entity/end_crystal/end_crystal.png"}
         : null;
   }

   public static Path resolveTexture(Path var0, String var1) {
      return "placed_end_crystal".equals(var1)
         ? var0.resolve("entity").resolve("end_crystal").resolve("end_crystal.png")
         : var0.resolve("item").resolve(var1 + ".png");
   }

   static Path packDir() {
      return gameDir().resolve("resourcepacks").resolve(PACK_NAME);
   }

   /** Resource pack format of the running game, read from its version.json: {major, minor}. */
   static int[] packFormat() {
      if (packFormat == null) {
         int major = -1;
         int minor = 0;

         try (InputStream in = class_310.class.getResourceAsStream("/version.json")) {
            if (in != null) {
               String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
               Matcher m = Pattern.compile("\"resource_major\"\\s*:\\s*(\\d+)").matcher(json);
               if (m.find()) {
                  major = Integer.parseInt(m.group(1));
                  Matcher n = Pattern.compile("\"resource_minor\"\\s*:\\s*(\\d+)").matcher(json);
                  if (n.find()) {
                     minor = Integer.parseInt(n.group(1));
                  }
               } else {
                  m = Pattern.compile("\"resource\"\\s*:\\s*(\\d+)").matcher(json);
                  if (m.find()) {
                     major = Integer.parseInt(m.group(1));
                  }
               }
            }
         } catch (Throwable var8) {
         }

         packFormat = new int[]{major < 0 ? 46 : major, minor};
      }

      return packFormat;
   }

   /** pack.mcmeta that the running game accepts without the "made for another version" warning. */
   static String packMeta() {
      int[] f = packFormat();
      return f[0] >= 65
         ? "{\"pack\":{\"description\":\"" + DESCRIPTION + "\",\"min_format\":" + f[0] + ",\"max_format\":" + f[0] + "}}"
         : "{\"pack\":{\"pack_format\":" + f[0] + ",\"description\":\"" + DESCRIPTION + "\"}}";
   }

   static void ensurePackMeta(Path pack) throws IOException {
      Files.createDirectories(pack);
      Path meta = pack.resolve("pack.mcmeta");
      String want = packMeta();
      String have = Files.isRegularFile(meta) ? Files.readString(meta, StandardCharsets.UTF_8) : null;
      if (!want.equals(have)) {
         Files.writeString(meta, want, StandardCharsets.UTF_8);
      }
   }

   /**
    * Turns the edits pack on (on top of the other packs), stores that in options.txt so it is still on after a
    * restart, and reloads the textures.
    */
   static String enableAndReload(class_310 mc) {
      class_3283 repo = mc.method_1520();
      repo.method_14445();
      String id = null;

      for (class_3288 pack : repo.method_14441()) {
         String pid = pack.method_14463();
         if (pid.equals("file/" + PACK_NAME) || pid.equals(PACK_NAME)) {
            id = pid;
            break;
         }
      }

      if (id == null) {
         for (class_3288 pack : repo.method_14441()) {
            if (pack.method_14463().contains(PACK_NAME)) {
               id = pack.method_14463();
               break;
            }
         }
      }

      if (id == null) {
         return "Saved the PNG, but the game did not detect the pack folder. Open the Resource Packs menu once, then save again.";
      } else {
         List<String> ids = new ArrayList<>();

         for (class_3288 pack : repo.method_14444()) {
            if (!pack.method_14463().equals(id)) {
               ids.add(pack.method_14463());
            }
         }

         ids.add(id);
         repo.method_14447(ids);
         class_315 options = mc.field_1690;
         List<String> before = new ArrayList<>(options.field_1887);
         options.method_49598(repo);
         if (before.equals(options.field_1887)) {
            mc.method_1521();
         }

         return "Saved and applied - the pack stays on after a restart.";
      }
   }

   /**
    * Where the texture of an item lives. Block items use textures/block/..., some items use another name than
    * their id, so the item model definition and its model parents are read first; the plain guesses follow.
    */
   static List<class_2960> textureCandidates(class_3300 rm, class_2960 item) {
      String ns = item.method_12836();
      String path = item.method_12832();
      LinkedHashSet<String> refs = new LinkedHashSet<>();

      try {
         List<String> models = new ArrayList<>();
         class_2960 definition = class_2960.method_12829(ns + ":items/" + path + ".json");
         if (definition != null) {
            collectModels(readJson(rm, definition), models);
         }

         if (models.isEmpty()) {
            models.add(ns + ":item/" + path);
         }

         for (String model : models) {
            String tex = modelTexture(rm, model);
            if (tex != null) {
               refs.add(tex);
            }
         }
      } catch (Throwable var9) {
      }

      refs.add(ns + ":item/" + path);
      refs.add(ns + ":block/" + path);
      refs.add(ns + ":item/" + path + "_00");
      refs.add(ns + ":block/" + path + "_side");
      refs.add(ns + ":block/" + path + "_top");
      refs.add(ns + ":block/" + path + "_front");
      List<class_2960> out = new ArrayList<>();

      for (String ref : refs) {
         class_2960 id = textureId(ref);
         if (id != null && !out.contains(id)) {
            out.add(id);
         }
      }

      return out;
   }

   private static class_2960 textureId(String ref) {
      if (ref == null || ref.isEmpty() || ref.startsWith("#")) {
         return null;
      } else {
         int colon = ref.indexOf(58);
         String ns = colon >= 0 ? ref.substring(0, colon) : "minecraft";
         String path = colon >= 0 ? ref.substring(colon + 1) : ref;
         return path.isEmpty() ? null : class_2960.method_12829(ns + ":textures/" + path + ".png");
      }
   }

   private static void collectModels(JsonElement e, List<String> out) {
      if (e != null && out.size() < 12) {
         if (e.isJsonObject()) {
            for (Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
               JsonElement v = en.getValue();
               if (("model".equals(en.getKey()) || "base".equals(en.getKey())) && v.isJsonPrimitive()) {
                  String s = v.getAsString();
                  if (!out.contains(s)) {
                     out.add(s);
                  }
               } else {
                  collectModels(v, out);
               }
            }
         } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
               collectModels(x, out);
            }
         }
      }
   }

   private static String modelTexture(class_3300 rm, String model) {
      Map<String, String> textures = new LinkedHashMap<>();
      String current = model;

      for (int depth = 0; depth < 12 && current != null; depth++) {
         int colon = current.indexOf(58);
         String ns = colon >= 0 ? current.substring(0, colon) : "minecraft";
         String path = colon >= 0 ? current.substring(colon + 1) : current;
         if (path.startsWith("builtin/")) {
            break;
         }

         class_2960 id = class_2960.method_12829(ns + ":models/" + path + ".json");
         JsonElement el = id == null ? null : readJson(rm, id);
         if (el == null || !el.isJsonObject()) {
            break;
         }

         JsonObject o = el.getAsJsonObject();
         if (o.has("textures") && o.get("textures").isJsonObject()) {
            for (Entry<String, JsonElement> en : o.getAsJsonObject("textures").entrySet()) {
               if (!textures.containsKey(en.getKey())) {
                  JsonElement v = en.getValue();
                  if (v.isJsonPrimitive()) {
                     textures.put(en.getKey(), v.getAsString());
                  } else if (v.isJsonObject() && v.getAsJsonObject().has("sprite") && v.getAsJsonObject().get("sprite").isJsonPrimitive()) {
                     textures.put(en.getKey(), v.getAsJsonObject().get("sprite").getAsString());
                  }
               }
            }
         }

         current = o.has("parent") && o.get("parent").isJsonPrimitive() ? o.get("parent").getAsString() : null;
      }

      for (String k : PREFERRED_TEXTURE_KEYS) {
         String v = resolveRef(textures, textures.get(k));
         if (v != null) {
            return v;
         }
      }

      for (String v : textures.values()) {
         String r = resolveRef(textures, v);
         if (r != null) {
            return r;
         }
      }

      return null;
   }

   private static String resolveRef(Map<String, String> textures, String v) {
      for (int i = 0; v != null && v.startsWith("#") && i < 10; i++) {
         v = textures.get(v.substring(1));
      }

      return v != null && !v.startsWith("#") ? v : null;
   }

   private static JsonElement readJson(class_3300 rm, class_2960 id) {
      try {
         Optional<class_3298> r = rm.method_14486(id);
         if (r.isEmpty()) {
            return null;
         } else {
            try (Reader in = new InputStreamReader(r.get().method_14482(), StandardCharsets.UTF_8)) {
               return JsonParser.parseReader(in);
            }
         }
      } catch (Throwable var7) {
         return null;
      }
   }

   static byte[] readBytes(class_3300 rm, class_2960 id) {
      try {
         Optional<class_3298> r = rm.method_14486(id);
         if (r.isEmpty()) {
            return null;
         } else {
            try (InputStream in = r.get().method_14482()) {
               return in.readAllBytes();
            }
         }
      } catch (Throwable var7) {
         return null;
      }
   }

   public static void exportZip(Object var0) {
      if (!EXPORTING.compareAndSet(false, true)) {
         setMessage(var0, "Export already running...");
      } else {
         setMessage(var0, "Choose where to save the ZIP...");
         Thread var1 = new Thread(() -> {
            try {
               setMessage(var0, runExport());
            } catch (Throwable var5) {
               setMessage(var0, "Could not export ZIP: " + var5.getMessage());
            } finally {
               EXPORTING.set(false);
            }
         }, "Visual Client Texture Export");
         var1.setDaemon(true);
         var1.start();
      }
   }

   private static String runExport() throws IOException {
      Path var0 = gameDir();
      Path var1 = var0.resolve("resourcepacks").resolve(PACK_NAME);
      Path var2 = var1.resolve("assets");
      if (Files.isDirectory(var2) && containsPng(var2)) {
         Path var3 = var0.resolve("visualclient").resolve("texture-exports");
         Files.createDirectories(var3);
         Path var4 = var3.resolve(PACK_NAME + ".zip");
         TexturePainterExtras.DialogResult var6 = saveDialog(var4);
         if (var6.cancelled) {
            return "ZIP export cancelled.";
         } else {
            Path var5 = var6.path != null ? var6.path : var4;
            if (!var5.getFileName().toString().toLowerCase().endsWith(".zip")) {
               var5 = var5.resolveSibling(var5.getFileName() + ".zip");
            }

            if (var5.toAbsolutePath().normalize().startsWith(var1.toAbsolutePath().normalize())) {
               return "Please pick a location outside the pack folder.";
            } else {
               int var7 = writeZip(var1, var5);
               return "Exported " + var7 + " file(s) to " + var5.toAbsolutePath();
            }
         }
      } else {
         return "Nothing to export yet - paint a texture and press Save first.";
      }
   }

   private static boolean containsPng(Path var0) throws IOException {
      try (Stream<Path> var1 = Files.walk(var0)) {
         return var1.anyMatch(var0x -> var0x.getFileName().toString().toLowerCase().endsWith(".png"));
      }
   }

   static int writeZip(Path var0, Path var1) throws IOException {
      ArrayList<Path> var2 = new ArrayList<>();

      try (Stream<Path> var3 = Files.walk(var0)) {
         var3.filter(var0x -> Files.isRegularFile(var0x)).sorted().forEach(var2::add);
      }

      Path var27 = var1.toAbsolutePath().getParent();
      if (var27 != null) {
         Files.createDirectories(var27);
      }

      Path var4 = Files.createTempFile(var27 != null ? var27 : Path.of("."), "vc-export", ".zip.tmp");
      int var5 = 0;

      try {
         try (
            OutputStream var6 = Files.newOutputStream(var4);
            ZipOutputStream var7 = new ZipOutputStream(var6);
         ) {
            boolean var8 = false;

            for (Path var10 : var2) {
               String var11 = var0.relativize(var10).toString().replace('\\', '/');
               if (var11.equals("pack.mcmeta")) {
                  var8 = true;
               }

               var7.putNextEntry(new ZipEntry(var11));
               Files.copy(var10, var7);
               var7.closeEntry();
               var5++;
            }

            if (!var8) {
               var7.putNextEntry(new ZipEntry("pack.mcmeta"));
               var7.write(packMeta().getBytes(StandardCharsets.UTF_8));
               var7.closeEntry();
            }
         }

         Files.move(var4, var1, StandardCopyOption.REPLACE_EXISTING);
      } finally {
         Files.deleteIfExists(var4);
      }

      return var5;
   }

   private static TexturePainterExtras.DialogResult saveDialog(Path var0) {
      try {
         Class<?> var1 = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
         Class<?> var2 = Class.forName("org.lwjgl.PointerBuffer");
         Method var3 = var1.getMethod("tinyfd_saveFileDialog", CharSequence.class, CharSequence.class, var2, CharSequence.class);
         long var4 = System.nanoTime();
         Object var6 = var3.invoke(null, "Save painted textures as ZIP", var0.toAbsolutePath().toString(), null, "Resource pack (*.zip)");
         if (var6 != null && !var6.toString().isBlank()) {
            return new TexturePainterExtras.DialogResult(Path.of(var6.toString()), false);
         } else {
            return System.nanoTime() - var4 < 400000000L
               ? new TexturePainterExtras.DialogResult(null, false)
               : new TexturePainterExtras.DialogResult(null, true);
         }
      } catch (Throwable var7) {
         return new TexturePainterExtras.DialogResult(null, false);
      }
   }

   private static Path gameDir() {
      try {
         Class<?> var0 = Class.forName("net.fabricmc.loader.api.FabricLoader");
         Object var1 = var0.getMethod("getInstance").invoke(null);
         return (Path)var0.getMethod("getGameDir").invoke(var1);
      } catch (Throwable var2) {
         return Path.of("").toAbsolutePath();
      }
   }

   private static void setMessage(Object var0, String var1) {
      try {
         Field var2 = var0.getClass().getDeclaredField("saveMessage");
         var2.setAccessible(true);
         var2.set(var0, var1);
      } catch (Throwable var3) {
      }
   }

   private record DialogResult(Path path, boolean cancelled) {
   }
}
