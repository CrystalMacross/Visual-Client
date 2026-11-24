package net.visualclient.modules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.hud.HudModule;
import net.visualclient.modules.combat.ArmorHudModule;
import net.visualclient.modules.combat.DamageTintModule;
import net.visualclient.modules.combat.HitIndicatorModule;
import net.visualclient.modules.combat.PotionHudModule;
import net.visualclient.modules.combat.ShieldFixesModule;
import net.visualclient.modules.combat.ShieldStatusModule;
import net.visualclient.modules.combat.TotemCounterModule;
import net.visualclient.modules.combat.TotemTweaksModule;
import net.visualclient.modules.extra.CustomHitSoundsModule;
import net.visualclient.modules.extra.CustomPopSoundsModule;
import net.visualclient.modules.extra.KillEffectsModule;
import net.visualclient.modules.extra.NoExplosionSoundsModule;
import net.visualclient.modules.extra.NoTotemPopSoundsModule;
import net.visualclient.modules.misc.ChatHeadsModule;
import net.visualclient.modules.misc.ConfigsModule;
import net.visualclient.modules.misc.CoordinatesModule;
import net.visualclient.modules.misc.CpsCounterModule;
import net.visualclient.modules.misc.CustomFontsModule;
import net.visualclient.modules.misc.FakeFpsModule;
import net.visualclient.modules.misc.FpsModule;
import net.visualclient.modules.misc.GuiStyleModule;
import net.visualclient.modules.misc.KeystrokesModule;
import net.visualclient.modules.misc.PinModule;
import net.visualclient.modules.misc.PresenceModule;
import net.visualclient.modules.misc.PingModule;
import net.visualclient.modules.misc.ThemeModule;
import net.visualclient.modules.player.AutoSprintModule;
import net.visualclient.modules.player.HighlighterModule;
import net.visualclient.modules.player.SaturationModule;
import net.visualclient.modules.player.ShulkerTooltipModule;
import net.visualclient.modules.player.SmoothSwapModule;
import net.visualclient.modules.visuals.ArmorGlintModule;
import net.visualclient.modules.visuals.BlockOverlayModule;
import net.visualclient.modules.visuals.FpsBoostModule;
import net.visualclient.modules.visuals.FullbrightModule;
import net.visualclient.modules.visuals.HideArmorModule;
import net.visualclient.modules.visuals.MotionBlurModule;
import net.visualclient.modules.visuals.NametagsModule;
import net.visualclient.modules.visuals.SkyColorModule;
import net.visualclient.modules.visuals.TexturePainterModule;
import net.visualclient.modules.visuals.WeatherModule;
import net.visualclient.modules.visuals.XpClumpsModule;
import net.visualclient.modules.visuals.ZoomModule;

public final class Modules {
   public static final FakeFpsModule FAKE_FPS = new FakeFpsModule();
   public static final List<ClientModule> ALL = ConfigsModule.withConfigs(
      new ClientModule[]{
         DamageTintModule.INSTANCE,
         new ArmorHudModule(),
         new TotemCounterModule(),
         new HitIndicatorModule(),
         new PotionHudModule(),
         WeatherModule.INSTANCE,
         SkyColorModule.INSTANCE,
         ArmorGlintModule.INSTANCE,
         FullbrightModule.INSTANCE,
         HideArmorModule.INSTANCE,
         new AutoSprintModule(),
         new SaturationModule(),
         ShulkerTooltipModule.INSTANCE,
         new FpsModule(),
         TexturePainterModule.INSTANCE,
         FAKE_FPS,
         new CoordinatesModule(),
         new PingModule(),
         PinModule.INSTANCE,
         PresenceModule.INSTANCE,
         null,
         ThemeModule.INSTANCE,
         TotemTweaksModule.INSTANCE,
         NametagsModule.INSTANCE,
         CustomFontsModule.INSTANCE,
         MotionBlurModule.INSTANCE,
         new KeystrokesModule(),
         new CpsCounterModule(),
         GuiStyleModule.INSTANCE,
         ShieldFixesModule.INSTANCE,
         ShieldStatusModule.INSTANCE,
         BlockOverlayModule.INSTANCE,
         XpClumpsModule.INSTANCE,
         ZoomModule.INSTANCE,
         HighlighterModule.INSTANCE,
         SmoothSwapModule.INSTANCE,
         ChatHeadsModule.INSTANCE,
         NoExplosionSoundsModule.INSTANCE,
         NoTotemPopSoundsModule.INSTANCE,
         CustomPopSoundsModule.INSTANCE,
         CustomHitSoundsModule.INSTANCE,
         KillEffectsModule.INSTANCE,
         FpsBoostModule.INSTANCE
      }
   );
   private static final Map<Category, List<ClientModule>> BY_CATEGORY = new EnumMap<>(Category.class);
   private static final List<HudModule> HUD = new ArrayList<>();

   private Modules() {
   }

   public static List<ClientModule> of(Category var0) {
      return BY_CATEGORY.get(var0);
   }

   public static List<HudModule> hud() {
      return HUD;
   }

   static {
      for (Category var3 : Category.values()) {
         BY_CATEGORY.put(var3, new ArrayList<>());
      }

      for (ClientModule var5 : ALL) {
         BY_CATEGORY.get(var5.category()).add(var5);
         if (var5 instanceof HudModule var6) {
            HUD.add(var6);
         }
      }
   }
}
