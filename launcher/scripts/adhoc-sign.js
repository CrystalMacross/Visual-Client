// electron-builder afterPack hook: ad-hoc signs the macOS app ("codesign -s -").
// Without any signature, macOS calls a downloaded app "damaged" and offers only "Move to Bin".
// Ad-hoc signed, it shows the normal warning for apps from the internet instead, which the user
// can confirm with right-click -> Open. (Notarization would need a paid Apple Developer ID.)
const { execFileSync } = require('child_process');
const path = require('path');

exports.default = async function adhocSign(context) {
  if (context.electronPlatformName !== 'darwin') return;
  const app = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
  console.log('  • ad-hoc signing ' + app);
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', app], { stdio: 'inherit' });
  execFileSync('codesign', ['--verify', '--deep', '--strict', '--verbose=2', app], { stdio: 'inherit' });
};
