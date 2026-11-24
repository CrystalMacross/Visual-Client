package net.visualstyles.style;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.class_1043;
import net.minecraft.class_2960;
import net.minecraft.class_310;

/** Preview pictures for the menu, uploaded as dynamic textures and freed when the menu closes. */
public final class Previews {
   private static final Map<String, class_2960> IDS = new HashMap<>();
   private static final Map<class_2960, int[]> SIZES = new HashMap<>();
   private static int next;

   private Previews() {
   }

   public static class_2960 get(Coll c, Style s) {
      String key = c.id + "|" + s.id;
      class_2960 id = IDS.get(key);
      if (id != null) {
         return id;
      }
      Pic pic = null;
      try {
         pic = c.preview(path -> Styles.render(c, s, path));
      } catch (Exception ignored) {
         pic = null;
      }
      if (pic == null || pic.w <= 0 || pic.h <= 0) {
         pic = new Pic(16, 16);
      }
      id = class_2960.method_60655("visualstyles", "preview/" + next++);
      class_1043 texture = new class_1043(() -> "visualstyles preview", pic.toNative());
      class_310.method_1551().method_1531().method_4616(id, texture);
      IDS.put(key, id);
      SIZES.put(id, new int[]{pic.w, pic.h});
      return id;
   }

   public static int[] size(class_2960 id) {
      int[] s = SIZES.get(id);
      return s != null ? s : new int[]{16, 16};
   }

   /** Frees every preview (menu closed, or extras changed so previews must be rebuilt). */
   public static void clear() {
      for (class_2960 id : IDS.values()) {
         try {
            class_310.method_1551().method_1531().method_4615(id);
         } catch (Exception ignored) {
         }
      }
      IDS.clear();
      SIZES.clear();
   }
}
