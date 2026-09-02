import {
  AbilityConstant,
  UIAbility,
  Want
} from '@kit.AbilityKit'

import {
  window
} from '@kit.ArkUI'

import {
  BusinessError
} from '@kit.BasicServicesKit'

import {
  notificationManager
} from '@kit.NotificationKit'

import {
  WalkieBackgroundTask
} from '../WalkieBackgroundTask'


/*
 * ============================================================
 * WALKIE HarmonyOS
 *
 * V24.5
 *
 * EntryAbility
 *
 * 目标：
 *
 * 1. 正常启动 WALKIE
 * 2. 启动长期后台任务
 * 3. 请求通知显示权限
 * 4. 锁屏后继续运行
 * 5. 后台任务通知点击后回到 WALKIE
 *
 * ============================================================
 */

export default class EntryAbility
  extends UIAbility {


  /*
   * ==========================================================
   * Ability 创建
   * ==========================================================
   */
  onCreate(
    want: Want,
    launchParam: AbilityConstant.LaunchParam
  ): void {

    console.info(
      'WALKIE V24.5: EntryAbility.onCreate'
    )


    /*
     * ========================================================
     * 启动后台长时任务
     *
     * 不等待。
     *
     * 后台任务独立运行。
     * ========================================================
     */
    void WalkieBackgroundTask.start(
      this.context
    )
  }


  /*
   * ==========================================================
   * 创建窗口
   * ==========================================================
   */
  onWindowStageCreate(
    windowStage: window.WindowStage
  ): void {

    console.info(
      'WALKIE V24.5: WindowStage 创建'
    )


    /*
     * ========================================================
     * 加载 WALKIE 主页面
     * ========================================================
     */
    windowStage.loadContent(
      'pages/Index',
      (
        err: BusinessError
      ): void => {

        if (
          err.code
        ) {

          console.error(
            'WALKIE V24.5: 主页面加载失败 ' +
              `code=${err.code} ` +
              `message=${err.message}`
          )

          return
        }


        console.info(
          'WALKIE V24.5: 主页面加载成功'
        )


        /*
         * ====================================================
         * 请求通知权限
         *
         * 官方要求：
         * loadContent 成功以后才能请求。
         *
         * 这一步非常重要：
         *
         * 如果应用通知开关关闭，
         * 系统连续任务通知可能不会正常出现在
         * 通知栏。
         * ====================================================
         */
        void EntryAbility
          .requestNotificationPermission(
            this.context
          )
      }
    )
  }


  /*
   * ==========================================================
   * 请求通知权限
   * ==========================================================
   */
  private static async requestNotificationPermission(
    context: import('@kit.AbilityKit')
      .common.UIAbilityContext
  ): Promise<void> {

    try {

      const enabled:
        boolean =
        await notificationManager
          .isNotificationEnabled()

      if (
        enabled
      ) {

        console.info(
          'WALKIE V24.5: 通知已经开启'
        )

        return
      }


      console.info(
        'WALKIE V24.5: 请求开启通知权限'
      )


      await notificationManager
        .requestEnableNotification(
          context
        )


      console.info(
        'WALKIE V24.5: 通知权限请求完成'
      )

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError


      console.error(
        'WALKIE V24.5: ' +
          '请求通知权限失败 ' +
          `code=${businessError.code} ` +
          `message=${businessError.message}`
      )
    }
  }


  /*
   * ==========================================================
   * WindowStage 销毁
   * ==========================================================
   */
  onWindowStageDestroy(): void {

    console.info(
      'WALKIE V24.5: WindowStage 销毁'
    )
  }


  /*
   * ==========================================================
   * 进入前台
   * ==========================================================
   */
  onForeground(): void {

    console.info(
      'WALKIE V24.5: ★应用进入前台★'
    )


    /*
     * 如果之前的后台任务仍然存在，
     * WalkieBackgroundTask 会直接返回。
     *
     * 如果已经被系统回收，
     * 这里会重新申请。
     */
    void WalkieBackgroundTask.start(
      this.context
    )
  }


  /*
   * ==========================================================
   * 进入后台
   * ==========================================================
   */
  onBackground(): void {

    console.info(
      'WALKIE V24.5: ★应用进入后台★'
    )


    /*
     * 绝对不能在这里停止后台长时任务。
     *
     * WALKIE 要继续：
     *
     * UDP
     * KeepAlive
     * 音频接收
     * 音频播放
     */
  }


  /*
   * ==========================================================
   * Ability 销毁
   * ==========================================================
   */
  onDestroy(): void {

    console.info(
      'WALKIE V24.5: EntryAbility.onDestroy'
    )


    /*
     * 不主动停止后台任务。
     */
  }
}