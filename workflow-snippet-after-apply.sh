# 插入到 android-release / apply 流程中，位于：
#   bash mod/apply.sh
# 之后、gradlew 之前：

      - name: Disable MPV (inject + verify)
        run: |
          set -euo pipefail
          python3 mod/hooks/inject_disable_mpv.py .
          if grep -q "new MpvPlayerEngine" app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java; then
            echo "ERROR: new MpvPlayerEngine still present"; exit 1
          fi
          if ! grep -q 'KERNEL_ORDER = {EXO, IJK, SYSTEM}' app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java; then
            echo "ERROR: KERNEL_ORDER still lists MPV"; exit 1
          fi
          echo "MPV disable verified"
