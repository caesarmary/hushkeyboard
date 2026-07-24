# hushkeyboard ProGuard / R8 rules
#
# R8 auto-generates keep rules from AndroidManifest.xml, so HushKeyboardService
# and MainActivity are already protected without any explicit rule here.
# The rule below is belt-and-suspenders documentation: it makes explicit that
# the IME service must survive obfuscation so Android can bind to it by class name.

-keep public class com.hushkeyboard.HushKeyboardService