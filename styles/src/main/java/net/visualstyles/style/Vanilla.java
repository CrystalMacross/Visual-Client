package net.visualstyles.style;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_3264;
import net.minecraft.class_3268;
import net.minecraft.class_7367;

/**
 * Reads textures from the built-in vanilla pack (never from other packs, so a style always
 * starts from the original look). Paths are relative to assets/minecraft/.
 */
public final class Vanilla {
   private static final Map<String, Pic> PICS = new HashMap<>();
   private static final Map<String, byte[]> BYTES = new HashMap<>();
   private static final byte[] MISSING = new byte[0];
   /** Only for tests outside the running game (reads from the game jar instead); null in game. */
   public static java.util.function.Function<String, byte[]> testSource;

   private Vanilla() {
   }

   /** Raw file bytes (PNG or .mcmeta), or null if the vanilla pack has no such file. */
   public static byte[] bytes(String path) {
      byte[] cached = BYTES.get(path);
      if (cached != null) {
         return cached == MISSING ? null : cached;
      }
      byte[] data = null;
      if (testSource != null) {
         data = testSource.apply(path);
         BYTES.put(path, data == null ? MISSING : data);
         return data;
      }
      try {
         class_3268 pack = class_310.method_1551().method_45573();
         // PackResources.getResource(PackType.CLIENT_RESOURCES, Identifier)
         class_7367<InputStream> supplier = pack.method_14405(class_3264.field_14188, class_2960.method_60655("minecraft", path));
         if (supplier != null) {
            try (InputStream in = supplier.get()) {
               data = in.readAllBytes();
            }
         }
      } catch (Exception ignored) {
         data = null;
      }
      BYTES.put(path, data == null ? MISSING : data);
      return data;
   }

   /** A fresh copy of the vanilla texture, or null if it does not exist. */
   public static Pic pic(String path) {
      Pic cached = PICS.get(path);
      if (cached == null) {
         byte[] data = bytes(path);
         if (data == null) {
            return null;
         }
         try {
            cached = Pic.read(new java.io.ByteArrayInputStream(data));
         } catch (Exception e) {
            return null;
         }
         PICS.put(path, cached);
      }
      return cached.copy();
   }
}
