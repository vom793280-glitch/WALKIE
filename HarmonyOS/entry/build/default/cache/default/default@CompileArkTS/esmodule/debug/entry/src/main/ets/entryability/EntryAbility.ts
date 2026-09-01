import type AbilityConstant from "@ohos:app.ability.AbilityConstant";
import ConfigurationConstant from "@ohos:app.ability.ConfigurationConstant";
import UIAbility from "@ohos:app.ability.UIAbility";
import type Want from "@ohos:app.ability.Want";
import abilityAccessCtrl from "@ohos:abilityAccessCtrl";
import type { PermissionRequestResult } from "@ohos:abilityAccessCtrl";
import hilog from "@ohos:hilog";
import type window from "@ohos:window";
import type { BusinessError } from "@ohos:base";
const DOMAIN = 0x0000;
export default class EntryAbility extends UIAbility {
    // ============================================================
    // Ability 创建
    // ============================================================
    onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
        hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onCreate');
    }
    // ============================================================
    // Ability 销毁
    // ============================================================
    onDestroy(): void {
        hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onDestroy');
    }
    // ============================================================
    // WindowStage 创建
    // ============================================================
    onWindowStageCreate(windowStage: window.WindowStage): void {
        hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageCreate');
        /*
         * ========================================================
         * 加载主页面
         * ========================================================
         */
        windowStage.loadContent('pages/Index', (err: BusinessError) => {
            if (err.code) {
                hilog.error(DOMAIN, 'testTag', 'Failed to load the content. Cause: %{public}s', JSON.stringify(err));
                return;
            }
            hilog.info(DOMAIN, 'testTag', '%{public}s', 'Succeeded in loading the content.');
        });
        /*
         * ========================================================
         * 颜色模式
         * ========================================================
         */
        try {
            this.context
                .getApplicationContext()
                .setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET);
        }
        catch (err) {
            hilog.error(DOMAIN, 'testTag', 'Failed to set colorMode. Cause: %{public}s', JSON.stringify(err));
        }
        /*
         * ========================================================
         * 请求麦克风权限
         * ========================================================
         */
        void this.requestMicrophonePermission();
    }
    // ============================================================
    // 麦克风运行时权限
    // ============================================================
    private async requestMicrophonePermission(): Promise<void> {
        try {
            const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
            /*
             * 当前 UIAbility 的 Context。
             *
             * 官方推荐直接把 this.context
             * 传给 requestPermissionsFromUser。
             */
            const permissionResult: PermissionRequestResult = await atManager.requestPermissionsFromUser(this.context, [
                'ohos.permission.MICROPHONE'
            ]);
            hilog.info(DOMAIN, 'WALKIE_PERMISSION', 'permissions=%{public}s', JSON.stringify(permissionResult.permissions));
            hilog.info(DOMAIN, 'WALKIE_PERMISSION', 'authResults=%{public}s', JSON.stringify(permissionResult.authResults));
            hilog.info(DOMAIN, 'WALKIE_PERMISSION', 'dialogShownResults=%{public}s', JSON.stringify(permissionResult.dialogShownResults));
            /*
             * 第一个权限的结果：
             *
             * 0 = PERMISSION_GRANTED
             */
            if (permissionResult.authResults.length > 0 &&
                permissionResult.authResults[0] === 0) {
                hilog.info(DOMAIN, 'WALKIE_PERMISSION', '%{public}s', 'MICROPHONE 授权成功');
            }
            else {
                hilog.error(DOMAIN, 'WALKIE_PERMISSION', '%{public}s', 'MICROPHONE 未获得授权');
            }
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            hilog.error(DOMAIN, 'WALKIE_PERMISSION', 'MICROPHONE 权限申请失败，' +
                'code=%{public}s message=%{public}s', `${businessError.code}`, businessError.message);
        }
    }
    // ============================================================
    // WindowStage 销毁
    // ============================================================
    onWindowStageDestroy(): void {
        hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageDestroy');
    }
    // ============================================================
    // 前台
    // ============================================================
    onForeground(): void {
        hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onForeground');
    }
    // ============================================================
    // 后台
    // ============================================================
    onBackground(): void {
        hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onBackground');
    }
}
