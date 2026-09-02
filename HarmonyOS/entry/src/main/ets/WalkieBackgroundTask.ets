import {
  backgroundTaskManager
} from '@kit.BackgroundTasksKit'

import {
  common,
  wantAgent
} from '@kit.AbilityKit'

import type {
  WantAgent
} from '@kit.AbilityKit'

import {
  BusinessError
} from '@kit.BasicServicesKit'


/*
 * ============================================================
 * WALKIE HarmonyOS
 *
 * V24.5
 *
 * 后台长期运行 + 持续任务通知
 *
 * ============================================================
 */

export class WalkieBackgroundTask {

  /*
   * 是否已经启动后台长时任务
   */
  private static running:
    boolean = false

  /*
   * HarmonyOS 返回的持续任务通知 ID。
   *
   * 目前主要用于记录系统连续任务对应的通知。
   */
  private static notificationId:
    number = -1


  /*
   * ==========================================================
   * 启动后台长时任务
   * ==========================================================
   */
  public static async start(
    context: common.UIAbilityContext
  ): Promise<boolean> {

    /*
     * 已经运行：
     * 不重复申请。
     */
    if (
      WalkieBackgroundTask.running
    ) {

      console.info(
        'WALKIE BACKGROUND: 长时任务已经运行 ' +
          `notificationId=${WalkieBackgroundTask.notificationId}`
      )

      return true
    }

    try {

      /*
       * ========================================================
       * 创建通知点击后的 WantAgent
       *
       * 点击系统后台任务通知后，
       * 回到 EntryAbility。
       * ========================================================
       */
      const wantAgentInfo:
        wantAgent.WantAgentInfo = {

        wants: [
          {
            bundleName:
              context.abilityInfo.bundleName,

            abilityName:
              context.abilityInfo.name
          }
        ],

        /*
         * 当前 SDK / 官方示例使用 actionType。
         */
        actionType:
          wantAgent.OperationType.START_ABILITY,

        requestCode:
          1001,

        wantAgentFlags: [
          wantAgent.WantAgentFlags
            .UPDATE_PRESENT_FLAG
        ]
      }


      /*
       * 获取 WantAgent。
       *
       * 明确指定为 Object 转类型，
       * 避免当前 ArkTS SDK 对 WantAgent 导出方式
       * 产生类型冲突。
       */
      const wantAgentObject:
        WantAgent =
        await wantAgent.getWantAgent(
          wantAgentInfo
        )


      /*
       * ========================================================
       * WALKIE 长时后台任务
       *
       * audioPlayback：
       *   持续接收 / 播放对讲语音
       *
       * audioRecording：
       *   后台按住说话时使用
       * ========================================================
       */
      const backgroundModes:
        string[] = [
          'audioPlayback',
          'audioRecording'
        ]


      /*
       * ========================================================
       * 启动后台长时任务
       *
       * V24.5：
       *
       * 保存系统返回的 notificationId。
       * ========================================================
       */
      const result:
        backgroundTaskManager.ContinuousTaskNotification =
        await backgroundTaskManager
          .startBackgroundRunning(
            context,
            backgroundModes,
            wantAgentObject
          )


      /*
       * 保存系统生成的持续任务通知 ID。
       */
      WalkieBackgroundTask.notificationId =
        result.notificationId


      WalkieBackgroundTask.running =
        true


      console.info(
        'WALKIE BACKGROUND: ★V24.5 长时任务启动成功★'
      )

      console.info(
        'WALKIE BACKGROUND: ' +
          `notificationId=${result.notificationId}`
      )

      console.info(
        'WALKIE BACKGROUND: ' +
          'slotType=' +
          `${result.slotType}`
      )

      console.info(
        'WALKIE BACKGROUND: ' +
          'contentType=' +
          `${result.contentType}`
      )

      return true

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError


      WalkieBackgroundTask.running =
        false

      WalkieBackgroundTask.notificationId =
        -1


      console.error(
        'WALKIE BACKGROUND: ★长时任务启动失败★'
      )

      console.error(
        'WALKIE BACKGROUND: ' +
          `code=${businessError.code}`
      )

      console.error(
        'WALKIE BACKGROUND: ' +
          `message=${businessError.message}`
      )

      return false
    }
  }


  /*
   * ==========================================================
   * 停止后台任务
   * ==========================================================
   */
  public static async stop(
    context: common.UIAbilityContext
  ): Promise<void> {

    if (
      !WalkieBackgroundTask.running
    ) {
      return
    }

    try {

      await backgroundTaskManager
        .stopBackgroundRunning(
          context
        )

      console.info(
        'WALKIE BACKGROUND: 长时任务已停止'
      )

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError


      console.error(
        'WALKIE BACKGROUND: ' +
          '停止长时任务失败'
      )

      console.error(
        `WALKIE BACKGROUND: code=${businessError.code}`
      )

      console.error(
        `WALKIE BACKGROUND: message=${businessError.message}`
      )

    } finally {

      WalkieBackgroundTask.running =
        false

      WalkieBackgroundTask.notificationId =
        -1
    }
  }


  /*
   * ==========================================================
   * 判断是否正在运行
   * ==========================================================
   */
  public static isRunning():
    boolean {

    return WalkieBackgroundTask.running
  }


  /*
   * ==========================================================
   * 获取持续任务通知 ID
   * ==========================================================
   */
  public static getNotificationId():
    number {

    return WalkieBackgroundTask.notificationId
  }
}