-dontshrink
-dontoptimize
-useuniqueclassmembernames
-adaptclassstrings

-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod,Record,MethodParameters
-renamesourcefileattribute SourceFile

-keep class tk.funayd.bundleManager.BundleManager { *; }

-keepclasseswithmembers class * extends org.bukkit.plugin.java.JavaPlugin {
    public void onEnable();
    public void onDisable();
}

-keepclassmembers class * implements org.bukkit.command.CommandExecutor {
    public boolean onCommand(org.bukkit.command.CommandSender,org.bukkit.command.Command,java.lang.String,java.lang.String[]);
}

-keepclassmembers class * implements org.bukkit.command.TabCompleter {
    public java.util.List onTabComplete(org.bukkit.command.CommandSender,org.bukkit.command.Command,java.lang.String,java.lang.String[]);
}
