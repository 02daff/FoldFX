# FoldFX: iPhone Duo-style fold animation for the OPPO Find N5

While you open or close the phone, a full-screen overlay blurs and frosts whatever is on
screen. The effect follows the hinge angle, is strongest mid-fold and disappears when the
phone is fully open or closed. Touches pass straight through it.

## Build & install
1. Install Android Studio on your PC.
2. File > Open > pick this `FoldFX` folder. Let Gradle sync (it downloads the build tools).
3. On the N5: Settings > About device > tap "Version" 7 times > Developer options > USB debugging ON.
4. Plug in the phone and press Run ▶ in Android Studio.

## First run
1. Fold the phone slowly and watch the angle number. If it says "No hinge sensor" or only
   jumps between 0 and 180, the N5 doesn't expose a usable hinge angle to apps.
2. Tap "Allow display over other apps" and enable FoldFX.
3. Tap "Start fold animation".
4. Tap "Turn off battery optimization" and set FoldFX to unrestricted. Also, in the
   Recents screen, lock the app so ColorOS doesn't kill it.
5. If the frosted half is on the wrong side, tap "Moving half" to flip it.

## Where to tweak
- `FoldEffectView.kt`: the look (veil, panel gradient, light sweep, hinge shadow).
- `FoldOverlayService.kt`: timing. `HOLD_MS` is how long the effect lingers after movement,
  `0.3f` is the smoothing factor, and `pow(0.6f)` shapes the intensity curve.
- Max blur slider in the app.

## Limits
- A normal app can't capture the cover screen's content and move it to the inner screen
  like iOS does, so this blurs and frosts the live screen instead.
- Background blur needs Android 12+ cross-window blur, which some power-saving modes disable.
  Without it you still get the frost effect.
