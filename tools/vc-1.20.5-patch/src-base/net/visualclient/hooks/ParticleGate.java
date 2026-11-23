package net.visualclient.hooks;

import net.minecraft.class_2394;
import net.visualclient.modules.visuals.FpsBoostModule;

/**
 * FPS Boost thins out particles.
 *
 * Up to 1.20.4 the particle mixin answered ParticleEngine.createParticle with null. Vanilla callers such as
 * the firework explosion (FireworkParticles.Starter) cast the returned particle and call setColor / setTrail
 * on it right away, so every rocket with a firework star crashed the game with a NullPointerException.
 *
 * Now createParticle always returns the particle it made. A particle that FPS Boost drops is only kept out of
 * ParticleEngine.add, so it is never ticked or drawn, and callers still get a real object.
 */
public final class ParticleGate {
   private static boolean drop;

   private ParticleGate() {
   }

   /** createParticle HEAD: decide whether the particle made by this call is dropped. */
   public static void begin(class_2394 options) {
      boolean d = false;

      try {
         d = FpsBoostModule.dropParticle(options);
      } catch (Throwable ignored) {
      }

      drop = d;
   }

   /** createParticle RETURN: never let the decision leak into a later add call. */
   public static void end() {
      drop = false;
   }

   /** ParticleEngine.add HEAD: true when the particle that is being added should be skipped. */
   public static boolean consumeDrop() {
      if (drop) {
         drop = false;
         return true;
      } else {
         return false;
      }
   }
}
