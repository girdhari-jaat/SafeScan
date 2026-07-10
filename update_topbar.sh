sed -i 's/flashOn: Boolean,/flashMode: com.safescan.data.FlashMode,/g' android/app/src/main/java/com/safescan/ui/TopBar.kt
sed -i 's/onFlashChange: (Boolean) -> Unit,/onFlashChange: () -> Unit,/g' android/app/src/main/java/com/safescan/ui/TopBar.kt
sed -i '/Text("Flash")/c\                            Text("Flash: ${flashMode.name}")' android/app/src/main/java/com/safescan/ui/TopBar.kt
sed -i '/Switch(/d' android/app/src/main/java/com/safescan/ui/TopBar.kt
sed -i '/checked = flashOn,/d' android/app/src/main/java/com/safescan/ui/TopBar.kt
sed -i '/onCheckedChange = { onFlashChange(it) }/d' android/app/src/main/java/com/safescan/ui/TopBar.kt
sed -i 's/onClick = { }/onClick = { onFlashChange() }/g' android/app/src/main/java/com/safescan/ui/TopBar.kt
