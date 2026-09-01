import {
  WalkieClient,
  WalkieClientState,
  WalkieChannel,
  WalkieUser
} from '../WalkieClient'

import { WalkieTone } from '../WalkieTone'

import { common } from '@kit.AbilityKit'

import { preferences } from '@kit.ArkData'

/*
 * ============================================================
 * WALKIE HarmonyOS
 *
 * UI：
 *   对齐 Android WALKIE
 *
 * 通信：
 *   WalkieClient
 *
 * 当前版本：
 *   V23.3
 *
 * 当前阶段：
 *   UI + 真实 UDP 连接
 *
 * 暂未接：
 *   麦克风录音
 *   Opus 编码
 *   音频播放
 * ============================================================
 */

@Entry
@Component
struct Index {

  // ============================================================
  // WALKIE 客户端
  // ============================================================

  private walkieClient: WalkieClient =
    new WalkieClient()

  private walkieTone: WalkieTone =
    new WalkieTone()

  // ============================================================
  // 首页状态
  // ============================================================

  @State connected: boolean = false

  @State nickname: string = ''

  private nicknamePreferences:
  preferences.Preferences | null =
    null

  @State myUserId: string = ''

  @State currentChannel: string = 'public'

  @State currentOnlineCount: number = 0

  @State currentPrivate: boolean = false

  // ============================================================
  // 在线人员
  // ============================================================

  @State onlineUsers: WalkieUser[] = []

  // ============================================================
  // 频道
  // ============================================================

  @State channels: WalkieChannel[] = [
    {
      name: 'public',
      onlineCount: 0,
      isPrivate: false,
      requirePassword: false
    }
  ]

  // ============================================================
  // 网络
  // ============================================================

  @State networkLatency: number = -1

  @State networkLoss: number = 100

  @State networkQuality: string = '检测中'

  @State networkJitter: number = -1

  @State networkType: string = '检测中'

  // ============================================================
  // PTT
  // ============================================================

  @State talkStatus: string = 'NONE'

  @State pressing: boolean = false

  // ============================================================
  // 页面
  // ============================================================

  @State currentPage: string = 'home'

  // ============================================================
  // 消息
  // ============================================================

  @State channelMessage: string = ''

  // ============================================================
  // 昵称
  // ============================================================

  @State showNicknameDialog: boolean = false

  @State nicknameInput: string = ''

  // ============================================================
  // 创建频道
  // ============================================================

  @State showCreateChannelDialog: boolean = false

  @State createChannelName: string = ''

  @State createChannelPrivate: boolean = false

  @State createChannelPassword: string = ''

  // ============================================================
  // 加入私密频道
  // ============================================================

  @State showPasswordDialog: boolean = false

  @State passwordChannel: string = ''

  @State passwordInput: string = ''

  // ============================================================
  // 删除频道
  // ============================================================

  @State showDeleteDialog: boolean = false

  // ============================================================
  // 生命周期
  // ============================================================

  aboutToAppear(): void {

    /*
     * ============================================================
     * 先注册 WalkieClient 回调。
     *
     * WalkieClient.setStateCallback()
     * 会立即发送一次当前状态。
     *
     * 所以必须先注册，再读取本地昵称，
     * 防止 WalkieClient 的空昵称覆盖本地保存的昵称。
     * ============================================================
     */

    this.walkieClient.setStateCallback(
      (
        state: WalkieClientState
      ): void => {

        this.applyClientState(
          state
        )
      }
    )

    /*
     * ============================================================
     * 读取本地保存的昵称
     * ============================================================
     */

    const hostContext =
      this.getUIContext()
        .getHostContext()

    if (
      hostContext !== undefined
    ) {

      try {

        this.nicknamePreferences =
          preferences.getPreferencesSync(
            hostContext,
            {
              name:
              'walkie_profile'
            }
          )

        const savedNickname:
          string =
          this.nicknamePreferences.getSync(
            'nickname',
            ''
          ) as string

        if (
          savedNickname.length > 0
        ) {

          this.nickname =
            savedNickname

          this.nicknameInput =
            savedNickname

          console.info(
            'WALKIE: 已恢复保存的昵称=' +
              savedNickname
          )
        }

      } catch (
      error
      ) {

        console.error(
          'WALKIE: 读取昵称失败=' +
            JSON.stringify(error)
        )
      }
    }
  }

  aboutToDisappear(): void {

    /*
     * 页面退出时关闭 UDP。
     */

    void this.walkieClient.close()
  }

  // ============================================================
  // 系统返回 / 侧滑返回
  // ============================================================

  onBackPress(): boolean {

    if (
      this.currentPage !== 'home'
    ) {

      this.currentPage =
        'home'

      return true
    }

    return false
  }

  // ============================================================
  // 主页面
  // ============================================================

  build() {

    Stack() {

      Column() {

        if (
          this.currentPage ===
            'home'
        ) {

          this.homePage()

        } else if (
          this.currentPage ===
            'users'
        ) {

          this.usersPage()

        } else if (
          this.currentPage ===
            'channels'
        ) {

          this.channelsPage()

        } else if (
          this.currentPage ===
            'settings'
        ) {

          this.settingsPage()
        }
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#F6F7F9')

      if (
        this.showNicknameDialog
      ) {

        this.nicknameDialog()
      }

      if (
        this.showCreateChannelDialog
      ) {

        this.createChannelDialog()
      }

      if (
        this.showPasswordDialog
      ) {

        this.passwordDialog()
      }

      if (
        this.showDeleteDialog
      ) {

        this.deleteChannelDialog()
      }
    }
    .width('100%')
    .height('100%')
  }

  // ============================================================
  // 首页
  // ============================================================

  @Builder
  homePage() {

    Column() {

      // --------------------------------------------------------
      // 顶部
      // --------------------------------------------------------

      Row() {

        Column() {

          Text('兄弟对讲机')
            .fontSize(28)
            .fontWeight(FontWeight.Bold)
            .fontColor('#181B1F')

          Text('V23.3')
            .fontSize(13)
            .fontColor('#777E87')
            .margin({
              top: 3
            })

        }
        .alignItems(
          HorizontalAlign.Start
        )
        .layoutWeight(1)

        this.statusPill()
      }
      .width('100%')
      .padding({
        left: 16,
        right: 16,
        top: 18,
        bottom: 10
      })

      // --------------------------------------------------------
      // 主体
      // --------------------------------------------------------

      Scroll() {

        Column() {

          this.nicknameCard()

          this.serverCard()

          this.channelCard()

          this.networkCard()

          this.onlineUsersCard()

          this.bottomButtons()

          Blank()
            .height(110)

        }
        .width('100%')
        .padding({
          left: 15,
          right: 15,
          bottom: 10
        })
      }
      .layoutWeight(1)

      // --------------------------------------------------------
      // PTT
      // --------------------------------------------------------

      this.pttBar()
    }
    .width('100%')
    .height('100%')
  }

  // ============================================================
  // 在线 / 离线
  // ============================================================

  @Builder
  statusPill() {

    Row() {

      Circle()
        .width(9)
        .height(9)
        .fill(
          this.connected
            ? '#35C759'
            : '#FF3B30'
        )

      Text(
        this.connected
          ? '在线'
          : '离线'
      )
        .fontSize(13)
        .fontWeight(FontWeight.Bold)
        .fontColor('#4E555C')
        .margin({
          left: 6
        })

    }
    .padding({
      left: 11,
      right: 11,
      top: 7,
      bottom: 7
    })
    .backgroundColor('#FFFFFF')
    .borderRadius(20)
  }

  // ============================================================
  // 昵称
  // ============================================================

  @Builder
  nicknameCard() {

    Row() {

      Stack() {

        Circle()
          .width(54)
          .height(54)
          .fill('#E8EDF6')

        Text('👤')
          .fontSize(25)
      }

      Column() {

        Text('我的昵称')
          .fontSize(12)
          .fontColor('#858B93')

        Text(
          this.nickname.length > 0
            ? this.nickname
            : '未设置昵称'
        )
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor('#181B1F')
          .margin({
            top: 3
          })

      }
      .alignItems(
        HorizontalAlign.Start
      )
      .layoutWeight(1)
      .margin({
        left: 12
      })

      Button('修改')
        .height(38)
        .fontSize(13)
        .fontColor('#3C6FF0')
        .backgroundColor('#EDF2FF')
        .borderRadius(19)
        .onClick(() => {

          this.nicknameInput =
            this.nickname

          this.showNicknameDialog =
            true
        })
    }
    .width('100%')
    .padding(16)
    .backgroundColor('#FFFFFF')
    .borderRadius(18)
  }

  // ============================================================
  // 服务器
  // ============================================================

  @Builder
  serverCard() {

    Row() {

      Column() {

        Text('兄弟服务器')
          .fontSize(12)
          .fontColor('#858B93')

        Text(
          this.connected
            ? '已连接'
            : '未连接'
        )
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor('#181B1F')
          .margin({
            top: 3
          })

        Text(
          this.connected
            ? this.networkType
            : '服务器地址已隐藏'
        )
          .fontSize(12)
          .fontColor('#858B93')
          .margin({
            top: 4
          })

      }
      .alignItems(
        HorizontalAlign.Start
      )
      .layoutWeight(1)

      if (
        this.connected
      ) {

        Button('断开')
          .height(40)
          .fontSize(14)
          .fontColor('#D32F2F')
          .backgroundColor('#FFF0F0')
          .borderRadius(20)
          .onClick(() => {

            void this.disconnectServer()
          })

      } else {

        Button('连接')
          .height(40)
          .fontSize(14)
          .fontColor('#FFFFFF')
          .backgroundColor('#3C6FF0')
          .borderRadius(20)
          .onClick(() => {

            void this.connectServer()
          })
      }

    }
    .width('100%')
    .padding(16)
    .margin({
      top: 9
    })
    .backgroundColor(
      this.connected
        ? '#EEF5FF'
        : '#FFFFFF'
    )
    .borderRadius(18)
  }

  // ============================================================
  // 当前频道
  // ============================================================

  @Builder
  channelCard() {

    Column() {

      Row() {

        Column() {

          Text('当前频道')
            .fontSize(12)
            .fontColor('#858B93')

          Text(
            this.currentPrivate
              ? `🔒 ${this.currentChannel}`
              : `🌐 ${this.currentChannel}`
          )
            .fontSize(23)
            .fontWeight(FontWeight.Bold)
            .fontColor('#181B1F')
            .margin({
              top: 3
            })

          Text(
            `在线人数：${this.currentOnlineCount} 人`
          )
            .fontSize(13)
            .fontColor('#858B93')
            .margin({
              top: 3
            })

        }
        .alignItems(
          HorizontalAlign.Start
        )
        .layoutWeight(1)

        Button('管理')
          .height(38)
          .fontSize(13)
          .fontColor('#3C6FF0')
          .backgroundColor('#EDF2FF')
          .borderRadius(19)
          .enabled(
            this.connected
          )
          .onClick(() => {

            this.currentPage =
              'channels'
          })
      }
      .width('100%')
    }
    .width('100%')
    .padding(16)
    .margin({
      top: 9
    })
    .backgroundColor('#FFFFFF')
    .borderRadius(18)
  }

  // ============================================================
  // 网络
  // ============================================================

  @Builder
  networkCard() {

    Row() {

      Stack() {

        Circle()
          .width(42)
          .height(42)
          .fill('#E9EEF9')

        Text('📶')
          .fontSize(19)

      }

      Column() {

        Text('网络状态')
          .fontSize(15)
          .fontWeight(FontWeight.Bold)
          .fontColor('#181B1F')

        Text(
          this.buildNetworkSummary()
        )
          .fontSize(11)
          .fontColor('#858B93')
          .margin({
            top: 3
          })

      }
      .alignItems(
        HorizontalAlign.Start
      )
      .margin({
        left: 10
      })
      .layoutWeight(1)

      Column() {

        Text(
          this.networkLatency >= 0
            ? `${this.networkLatency} ms`
            : '--'
        )
          .fontSize(15)
          .fontWeight(FontWeight.Bold)

        Text('延迟')
          .fontSize(10)
          .fontColor('#858B93')
          .margin({
            top: 2
          })

      }
      .alignItems(
        HorizontalAlign.End
      )

    }
    .width('100%')
    .padding(13)
    .margin({
      top: 9
    })
    .backgroundColor('#FFFFFF')
    .borderRadius(18)
  }

  // ============================================================
  // 在线人员
  // ============================================================

  @Builder
  onlineUsersCard() {

    Column() {

      Row() {

        Column() {

          Text('在线人员')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor('#181B1F')

          Text(
            this.connected
              ? '当前频道成员'
              : '连接服务器后显示'
          )
            .fontSize(11)
            .fontColor('#858B93')
            .margin({
              top: 2
            })

        }
        .alignItems(
          HorizontalAlign.Start
        )
        .layoutWeight(1)

        Button('全部')
          .height(34)
          .fontSize(12)
          .fontColor('#3C6FF0')
          .backgroundColor('#EDF2FF')
          .borderRadius(17)
          .enabled(
            this.connected
          )
          .onClick(() => {

            this.currentPage =
              'users'
          })
      }
      .width('100%')

      if (
        !this.connected
      ) {

        this.emptyMiniCard(
          '当前未连接服务器'
        )

      } else if (
        this.onlineUsers.length === 0
      ) {

        this.emptyMiniCard(
          '当前暂无在线人员'
        )

      } else {

        ForEach(
          this.onlineUsers.slice(
            0,
            5
          ),
          (
            user: WalkieUser
          ) => {

            this.userRow(
              user
            )
          },
          (
            user: WalkieUser
          ) => {

            return user.userId
          }
        )
      }

    }
    .width('100%')
    .padding(13)
    .margin({
      top: 9
    })
    .backgroundColor('#FFFFFF')
    .borderRadius(18)
  }

  // ============================================================
  // 用户行
  // ============================================================

  @Builder
  userRow(
    user: WalkieUser
  ) {

    Row() {

      Circle()
        .width(30)
        .height(30)
        .fill('#E8EDF6')

      Text('👤')
        .fontSize(14)
        .position({
          x: 7,
          y: 4
        })

      Text(
        user.nickname.length > 0
          ? user.nickname
          : '未命名用户'
      )
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .margin({
          left: 8
        })
        .layoutWeight(1)

      Text('🟢')
        .fontSize(10)
    }
    .width('100%')
    .height(40)
    .padding({
      left: 7,
      right: 7
    })
    .margin({
      top: 5
    })
    .backgroundColor('#F5F7FA')
    .borderRadius(11)
  }

  // ============================================================
  // 空小卡片
  // ============================================================

  @Builder
  emptyMiniCard(
    text: string
  ) {

    Row() {

      Text(text)
        .fontSize(11)
        .fontColor('#858B93')

    }
    .width('100%')
    .height(42)
    .justifyContent(
      FlexAlign.Center
    )
    .backgroundColor('#F7F8FA')
    .borderRadius(11)
    .margin({
      top: 7
    })
  }

  // ============================================================
  // 底部按钮
  // ============================================================

  @Builder
  bottomButtons() {

    Row() {

      Button('频道')
        .layoutWeight(1)
        .height(42)
        .margin({
          right: 5
        })
        .fontSize(14)
        .fontColor('#3B4148')
        .backgroundColor('#FFFFFF')
        .borderRadius(14)
        .onClick(() => {

          this.currentPage =
            'channels'
        })

      Button('设置')
        .layoutWeight(1)
        .height(42)
        .margin({
          left: 5
        })
        .fontSize(14)
        .fontColor('#FFFFFF')
        .backgroundColor('#3C6FF0')
        .borderRadius(14)
        .onClick(() => {

          this.currentPage =
            'settings'
        })
    }
    .width('100%')
    .margin({
      top: 10
    })
  }

  // ============================================================
  // PTT
  // ============================================================

  @Builder
  pttBar() {

    Column() {

      Text(
        this.getTalkStatusText()
      )
        .fontSize(13)
        .fontWeight(FontWeight.Bold)
        .fontColor('#33383E')
        .margin({
          bottom: 6
        })

      Row() {

        Text(
          this.connected
            ? '🎙'
            : '🔇'
        )
          .fontSize(29)

        Text(
          this.getPttButtonText()
        )
          .fontSize(21)
          .fontWeight(FontWeight.Bold)
          .fontColor('#FFFFFF')
          .margin({
            left: 11
          })

      }
      .width('100%')
      .height(72)
      .justifyContent(
        FlexAlign.Center
      )
      .backgroundColor(
        this.getPttColor()
      )
      .borderRadius(21)
      .onTouch(
        (
          event: TouchEvent
        ) => {

          if (
            !this.connected
          ) {

            return
          }

          if (
            event.type ===
              TouchType.Down
          ) {

            this.pressing =
              true

            void this.walkieClient
              .startTalking()
          }

          if (
            event.type ===
              TouchType.Up ||
              event.type ===
                TouchType.Cancel
          ) {

            this.pressing =
              false

            void this.walkieClient
              .stopTalking()
          }
        }
      )
    }
    .width('100%')
    .padding({
      left: 15,
      right: 15,
      top: 8,
      bottom: 10
    })
    .backgroundColor('#FFFFFF')
  }

  // ============================================================
  // PTT 状态文字
  // ============================================================

  getTalkStatusText(): string {

    if (
      !this.connected
    ) {

      return '连接服务器后即可讲话'
    }

    if (
      this.talkStatus ===
        'ALLOWED'
    ) {

      return '正在讲话 · 松开结束'
    }

    if (
      this.talkStatus ===
        'REQUESTING'
    ) {

      return '正在抢麦…请保持按住'
    }

    if (
      this.talkStatus ===
        'BUSY'
    ) {

      return '频道正在通话'
    }

    if (
      this.pressing
    ) {

      return '讲话中'
    }

    return '按住说话'
  }

  // ============================================================
  // PTT 按钮文字
  // ============================================================

  getPttButtonText(): string {

    if (
      !this.connected
    ) {

      return '连接后使用'
    }

    if (
      this.talkStatus ===
        'ALLOWED'
    ) {

      return '松开结束讲话'
    }

    if (
      this.talkStatus ===
        'REQUESTING'
    ) {

      return '抢麦中 · 不要松开'
    }

    if (
      this.talkStatus ===
        'BUSY'
    ) {

      return '忙线'
    }

    return '按住说话'
  }

  // ============================================================
  // PTT 颜色
  // ============================================================

  getPttColor(): string {

    if (
      !this.connected
    ) {

      return '#9E9E9E'
    }

    if (
      this.talkStatus ===
        'ALLOWED'
    ) {

      return '#D32F2F'
    }

    if (
      this.talkStatus ===
        'REQUESTING'
    ) {

      return '#F57C00'
    }

    if (
      this.talkStatus ===
        'BUSY'
    ) {

      return '#616161'
    }

    if (
      this.pressing
    ) {

      return '#C62828'
    }

    return '#3C6FF0'
  }

  // ============================================================
  // 在线人员页面
  // ============================================================

  @Builder
  usersPage() {

    Column() {

      this.pageHeader(
        '在线人员',
        `频道：${this.currentChannel}`
      )

      Scroll() {

        Column() {

          Row() {

            Column() {

              Text('当前在线')
                .fontSize(11)
                .fontColor('#858B93')

              Text(
                this.connected
                  ? `${this.currentOnlineCount} 人`
                  : '未连接'
              )
                .fontSize(21)
                .fontWeight(FontWeight.Bold)
                .margin({
                  top: 3
                })

            }
            .layoutWeight(1)

            Text('👥')
              .fontSize(27)

          }
          .width('100%')
          .padding(15)
          .backgroundColor('#FFFFFF')
          .borderRadius(17)

          if (
            !this.connected
          ) {

            this.emptyCard(
              '当前未连接服务器'
            )

          } else if (
            this.onlineUsers.length === 0
          ) {

            this.emptyCard(
              '当前暂无在线人员'
            )

          } else {

            ForEach(
              this.onlineUsers,
              (
                user: WalkieUser
              ) => {

                this.largeUserRow(
                  user
                )
              },
              (
                user: WalkieUser
              ) => {

                return user.userId
              }
            )
          }

        }
        .width('100%')
        .padding(15)

      }
      .layoutWeight(1)

    }
    .width('100%')
    .height('100%')
  }

  // ============================================================
  // 用户大行
  // ============================================================

  @Builder
  largeUserRow(
    user: WalkieUser
  ) {

    Row() {

      Circle()
        .width(43)
        .height(43)
        .fill('#E8EDF6')

      Text('👤')
        .fontSize(20)
        .position({
          x: 10,
          y: 8
        })

      Column() {

        Text(
          user.nickname.length > 0
            ? user.nickname
            : '未命名用户'
        )
          .fontSize(16)
          .fontWeight(FontWeight.Bold)

        Text('在线')
          .fontSize(10)
          .fontColor('#858B93')
          .margin({
            top: 2
          })

      }
      .alignItems(
        HorizontalAlign.Start
      )
      .layoutWeight(1)
      .margin({
        left: 11
      })

      Text('🟢')
        .fontSize(12)
    }
    .width('100%')
    .padding(13)
    .margin({
      top: 7
    })
    .backgroundColor('#FFFFFF')
    .borderRadius(17)
  }

  // ============================================================
  // 空卡片
  // ============================================================

  @Builder
  emptyCard(
    text: string
  ) {

    Row() {

      Text(text)
        .fontSize(13)
        .fontColor('#858B93')

    }
    .width('100%')
    .height(65)
    .justifyContent(
      FlexAlign.Center
    )
    .backgroundColor('#FFFFFF')
    .borderRadius(17)
    .margin({
      top: 9
    })
  }

  // ============================================================
  // 频道页面
  // ============================================================

  @Builder
  channelsPage() {

    Column() {

      this.pageHeader(
        '频道',
        '切换和管理频道'
      )

      Row() {

        Button('＋ 创建')
          .layoutWeight(1)
          .height(42)
          .margin({
            right: 5
          })
          .enabled(
            this.connected
          )
          .fontColor('#FFFFFF')
          .backgroundColor('#3C6FF0')
          .borderRadius(12)
          .onClick(() => {

            this.showCreateChannelDialog =
              true
          })

        Button('刷新')
          .layoutWeight(1)
          .height(42)
          .margin({
            left: 5
          })
          .enabled(
            this.connected
          )
          .fontColor('#3B4148')
          .backgroundColor('#EEF1F5')
          .borderRadius(12)
          .onClick(() => {

            void this.walkieClient
              .requestChannelList()
          })
      }
      .width('100%')
      .padding({
        left: 15,
        right: 15,
        top: 10
      })

      Button('删除当前频道')
        .width('calc(100% - 30vp)')
        .height(40)
        .margin({
          top: 8,
          left: 15,
          right: 15
        })
        .enabled(
          this.connected &&
            this.currentChannel !==
              'public'
        )
        .fontColor('#D92F2F')
        .backgroundColor('#FFF0F0')
        .borderRadius(12)
        .onClick(() => {

          this.showDeleteDialog =
            true
        })

      Scroll() {

        Column() {

          ForEach(
            this.channels,
            (
              channel: WalkieChannel
            ) => {

              this.channelRow(
                channel
              )
            },
            (
              channel: WalkieChannel
            ) => {

              return channel.name
            }
          )

        }
        .width('100%')
        .padding(15)

      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }

  // ============================================================
  // 频道行
  // ============================================================

  @Builder
  channelRow(
    channel: WalkieChannel
  ) {

    Row() {

      Column() {

        Text(
          channel.isPrivate
            ? `🔒 ${channel.name}`
            : `🌐 ${channel.name}`
        )
          .fontSize(17)
          .fontWeight(FontWeight.Bold)

        Text(
          `在线 ${channel.onlineCount} 人`
        )
          .fontSize(11)
          .fontColor('#858B93')
          .margin({
            top: 3
          })

      }
      .alignItems(
        HorizontalAlign.Start
      )
      .layoutWeight(1)

      if (
        channel.name ===
          this.currentChannel
      ) {

        Text('当前')
          .fontSize(13)
          .fontWeight(FontWeight.Bold)
          .fontColor('#3C6FF0')

      } else {

        Button(
          channel.isPrivate ||
            channel.requirePassword
            ? '加入'
            : '切换'
        )
          .height(36)
          .fontSize(12)
          .backgroundColor('#EEF1F5')
          .fontColor('#3B4148')
          .borderRadius(12)
          .enabled(
            this.connected
          )
          .onClick(() => {

            this.selectChannel(
              channel
            )
          })
      }
    }
    .width('100%')
    .padding(14)
    .margin({
      top: 7
    })
    .backgroundColor('#FFFFFF')
    .borderRadius(16)
  }

  // ============================================================
  // 设置页面
  // ============================================================

  @Builder
  settingsPage() {

    Column() {

      this.pageHeader(
        '设置',
        '个人与应用信息'
      )

      Scroll() {

        Column() {

          Row() {

            Circle()
              .width(44)
              .height(44)
              .fill('#E8EDF6')

            Column() {

              Text('昵称')
                .fontSize(11)
                .fontColor('#858B93')

              Text(
                this.nickname.length > 0
                  ? this.nickname
                  : '未设置'
              )
                .fontSize(17)
                .fontWeight(FontWeight.Bold)
                .margin({
                  top: 3
                })
            }
            .alignItems(
              HorizontalAlign.Start
            )
            .margin({
              left: 10
            })
            .layoutWeight(1)

            Button('修改')
              .height(36)
              .fontSize(12)
              .backgroundColor('#EEF2FF')
              .fontColor('#3C6FF0')
              .borderRadius(18)
              .onClick(() => {

                this.nicknameInput =
                  this.nickname

                this.showNicknameDialog =
                  true
              })
          }
          .width('100%')
          .padding(14)
          .backgroundColor('#FFFFFF')
          .borderRadius(18)

          this.infoCard(
            '网络状态',
            this.buildNetworkSummary()
          )

          this.infoCard(
            '用户标识',
            this.myUserId.length > 0
              ? this.myUserId
              : '连接后由服务器分配'
          )

          this.infoCard(
            '服务器',
            '兄弟服务器'
          )

          this.infoCard(
            '版本',
            'V23.3'
          )
        }
        .width('100%')
        .padding(15)

      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }

  // ============================================================
  // 页面标题
  // ============================================================

  @Builder
  pageHeader(
    title: string,
    subtitle: string
  ) {

    Row() {

      Button('‹')
        .width(42)
        .height(42)
        .fontSize(27)
        .fontColor('#33383E')
        .backgroundColor('#FFFFFF')
        .borderRadius(21)
        .onClick(() => {

          this.currentPage =
            'home'
        })

      Column() {

        Text(title)
          .fontSize(23)
          .fontWeight(FontWeight.Bold)

        Text(subtitle)
          .fontSize(11)
          .fontColor('#858B93')
          .margin({
            top: 2
          })

      }
      .alignItems(
        HorizontalAlign.Start
      )
      .margin({
        left: 9
      })
    }
    .width('100%')
    .padding({
      left: 15,
      right: 15,
      top: 16,
      bottom: 7
    })
  }

  // ============================================================
  // 信息卡
  // ============================================================

  @Builder
  infoCard(
    title: string,
    value: string
  ) {

    Column() {

      Text(title)
        .fontSize(11)
        .fontColor('#858B93')

      Text(value)
        .fontSize(16)
        .fontWeight(FontWeight.Bold)
        .margin({
          top: 4
        })

    }
    .width('100%')
    .padding(14)
    .backgroundColor('#FFFFFF')
    .borderRadius(17)
    .margin({
      top: 8
    })
  }

  // ============================================================
  // 昵称弹窗
  // ============================================================

  @Builder
  nicknameDialog() {

    Column() {

      Column() {

        Text(
          this.nickname.length > 0
            ? '修改昵称'
            : '首次设置昵称'
        )
          .fontSize(21)
          .fontWeight(FontWeight.Bold)

        Text(
          this.nickname.length > 0
            ? '修改后会同步到服务器'
            : '请输入你的对讲机昵称'
        )
          .fontSize(12)
          .fontColor('#858B93')
          .margin({
            top: 5
          })

        TextInput({
          placeholder: '请输入昵称',
          text: this.nicknameInput
        })
          .width('100%')
          .height(48)
          .margin({
            top: 15
          })
          .onChange(
            (
              value: string
            ) => {

              this.nicknameInput =
                value
                  .substring(
                    0,
                    20
                  )
            })

        Row() {

          Button('取消')
            .layoutWeight(1)
            .height(42)
            .margin({
              right: 5
            })
            .fontColor('#41474E')
            .backgroundColor('#EEF1F5')
            .borderRadius(12)
            .onClick(() => {

              this.showNicknameDialog =
                false
            })

          Button('保存')
            .layoutWeight(1)
            .height(42)
            .margin({
              left: 5
            })
            .fontColor('#FFFFFF')
            .backgroundColor('#3C6FF0')
            .borderRadius(12)
            .enabled(
              this.nicknameInput
                .trim()
                .length > 0
            )
            .onClick(() => {

              void this.saveNickname()
            })

        }
        .width('100%')
        .margin({
          top: 16
        })
      }
      .width('86%')
      .padding(20)
      .backgroundColor('#FFFFFF')
      .borderRadius(20)

    }
    .width('100%')
    .height('100%')
    .backgroundColor('#00000066')
    .justifyContent(
      FlexAlign.Center
    )
  }

  // ============================================================
  // 创建频道弹窗
  // ============================================================

  @Builder
  createChannelDialog() {

    Column() {

      Column() {

        Text('创建频道')
          .fontSize(21)
          .fontWeight(FontWeight.Bold)

        TextInput({
          placeholder: '频道名称',
          text: this.createChannelName
        })
          .width('100%')
          .height(48)
          .margin({
            top: 15
          })
          .onChange(
            (
              value: string
            ) => {

              this.createChannelName =
                value
                  .substring(
                    0,
                    24
                  )
            })

        Button(
          this.createChannelPrivate
            ? '🔒 私密频道'
            : '🌐 公开频道'
        )
          .width('100%')
          .height(42)
          .margin({
            top: 9
          })
          .fontSize(13)
          .backgroundColor('#EEF1F5')
          .fontColor('#3B4148')
          .borderRadius(12)
          .onClick(() => {

            this.createChannelPrivate =
              !this.createChannelPrivate
          })

        if (
          this.createChannelPrivate
        ) {

          TextInput({
            placeholder: '频道密码',
            text: this.createChannelPassword
          })
            .width('100%')
            .height(48)
            .margin({
              top: 9
            })
            .onChange(
              (
                value: string
              ) => {

                this.createChannelPassword =
                  value
                    .substring(
                      0,
                      32
                    )
              })
        }

        Row() {

          Button('取消')
            .layoutWeight(1)
            .height(42)
            .margin({
              right: 5
            })
            .backgroundColor('#EEF1F5')
            .fontColor('#41474E')
            .borderRadius(12)
            .onClick(() => {

              this.resetCreateDialog()
            })

          Button('创建')
            .layoutWeight(1)
            .height(42)
            .margin({
              left: 5
            })
            .fontColor('#FFFFFF')
            .backgroundColor('#3C6FF0')
            .borderRadius(12)
            .enabled(
              this.createChannelName
                .trim()
                .length > 0 &&
                (
                  !this.createChannelPrivate ||
                    this.createChannelPassword
                      .trim()
                      .length > 0
                )
            )
            .onClick(() => {

              void this.createChannel()
            })
        }
        .width('100%')
        .margin({
          top: 16
        })

      }
      .width('88%')
      .padding(20)
      .backgroundColor('#FFFFFF')
      .borderRadius(20)

    }
    .width('100%')
    .height('100%')
    .backgroundColor('#00000066')
    .justifyContent(
      FlexAlign.Center
    )
  }

  // ============================================================
  // 私密频道密码
  // ============================================================

  @Builder
  passwordDialog() {

    Column() {

      Column() {

        Text('🔒 输入频道密码')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)

        Text(
          `频道：${this.passwordChannel}`
        )
          .fontSize(13)
          .fontWeight(FontWeight.Bold)
          .margin({
            top: 10
          })

        TextInput({
          placeholder: '请输入密码',
          text: this.passwordInput
        })
          .width('100%')
          .height(48)
          .margin({
            top: 12
          })
          .onChange(
            (
              value: string
            ) => {

              this.passwordInput =
                value
            })

        Row() {

          Button('取消')
            .layoutWeight(1)
            .height(42)
            .margin({
              right: 5
            })
            .fontColor('#41474E')
            .backgroundColor('#EEF1F5')
            .borderRadius(12)
            .onClick(() => {

              this.resetPasswordDialog()
            })

          Button('加入')
            .layoutWeight(1)
            .height(42)
            .margin({
              left: 5
            })
            .fontColor('#FFFFFF')
            .backgroundColor('#3C6FF0')
            .borderRadius(12)
            .enabled(
              this.passwordInput
                .trim()
                .length > 0
            )
            .onClick(() => {

              void this.joinPrivateChannel()
            })

        }
        .width('100%')
        .margin({
          top: 15
        })

      }
      .width('88%')
      .padding(20)
      .backgroundColor('#FFFFFF')
      .borderRadius(20)

    }
    .width('100%')
    .height('100%')
    .backgroundColor('#00000066')
    .justifyContent(
      FlexAlign.Center
    )
  }

  // ============================================================
  // 删除频道
  // ============================================================

  @Builder
  deleteChannelDialog() {

    Column() {

      Column() {

        Text('删除频道')
          .fontSize(21)
          .fontWeight(FontWeight.Bold)

        Text(
          `确定删除「${this.currentChannel}」吗？`
        )
          .fontSize(14)
          .margin({
            top: 12
          })

        Text(
          '删除后无法恢复。'
        )
          .fontSize(12)
          .fontColor('#858B93')
          .margin({
            top: 5
          })

        Row() {

          Button('取消')
            .layoutWeight(1)
            .height(42)
            .margin({
              right: 5
            })
            .fontColor('#41474E')
            .backgroundColor('#EEF1F5')
            .borderRadius(12)
            .onClick(() => {

              this.showDeleteDialog =
                false
            })

          Button('确定删除')
            .layoutWeight(1)
            .height(42)
            .margin({
              left: 5
            })
            .fontColor('#FFFFFF')
            .backgroundColor('#D32F2F')
            .borderRadius(12)
            .onClick(() => {

              void this.deleteCurrentChannel()
            })
        }
        .width('100%')
        .margin({
          top: 16
        })

      }
      .width('88%')
      .padding(20)
      .backgroundColor('#FFFFFF')
      .borderRadius(20)

    }
    .width('100%')
    .height('100%')
    .backgroundColor('#00000066')
    .justifyContent(
      FlexAlign.Center
    )
  }

  // ============================================================
  // 真实连接
  // ============================================================

  private async connectServer():
    Promise<void> {

    if (
      this.connected
    ) {

      return
    }

    const name: string =
      this.nickname.trim()

    /*
     * ============================================================
     * 连接前先把 UI 当前显示的昵称同步到 WalkieClient。
     *
     * 这样即使 APP 是重新打开的，
     * 本地恢复出来的昵称也会进入 WalkieClient.state.nickname。
     *
     * 后面的 WALKIE_LOGIN 就会携带正确昵称。
     * ============================================================
     */

    if (
      name.length > 0
    ) {

      console.info(
        'WALKIE CONNECT: 准备同步昵称=' +
          name
      )

      await this.walkieClient
        .setNickname(
          name
        )

      console.info(
        'WALKIE CONNECT: 昵称同步完成=' +
          name
      )

    }

    /*
     * 没有昵称时仍然允许连接。
     *
     * VPS 会按 deviceId 创建默认用户名。
     */

    await this.walkieClient.connect(
      name
    )
  }

  // ============================================================
  // 真实断开
  // ============================================================

  private async disconnectServer():
    Promise<void> {

    await this.walkieClient.disconnect()
  }

  // ============================================================
  // 保存昵称
  // ============================================================

  private async saveNickname():
    Promise<void> {

    const value:
      string =
      this.nicknameInput
        .trim()
        .substring(
          0,
          20
        )

    if (
      value.length === 0
    ) {

      return
    }

    this.nickname =
      value

    this.showNicknameDialog =
      false

    /*
     * 保存到手机本地。
     */

    if (
      this.nicknamePreferences !== null
    ) {

      try {

        this.nicknamePreferences.putSync(
          'nickname',
          value
        )

        this.nicknamePreferences.flushSync()

        console.info(
          'WALKIE: 昵称已保存=' +
            value
        )

      } catch (
      error
      ) {

        console.error(
          'WALKIE: 保存昵称失败=' +
            JSON.stringify(error)
        )
      }
    }

    /*
     * 同步服务器。
     */

    await this.walkieClient
      .setNickname(
        value
      )
  }

  // ============================================================
  // 创建频道
  // ============================================================

  private async createChannel():
    Promise<void> {

    const name: string =
      this.createChannelName
        .trim()

    if (
      name.length === 0
    ) {

      return
    }

    if (
      this.createChannelPrivate &&
        this.createChannelPassword
          .trim()
          .length === 0
    ) {

      return
    }

    await this.walkieClient
      .createChannel(
        name,
        this.createChannelPrivate,
        this.createChannelPassword
          .trim()
      )

    this.resetCreateDialog()
  }

  // ============================================================
  // 重置创建频道
  // ============================================================

  private resetCreateDialog(): void {

    this.showCreateChannelDialog =
      false

    this.createChannelName =
      ''

    this.createChannelPrivate =
      false

    this.createChannelPassword =
      ''
  }

  // ============================================================
  // 选择频道
  // ============================================================

  private selectChannel(
    channel: WalkieChannel
  ): void {

    if (
      !this.connected
    ) {

      return
    }

    if (
      channel.name ===
        this.currentChannel
    ) {

      return
    }

    if (
      channel.isPrivate ||
        channel.requirePassword
    ) {

      this.passwordChannel =
        channel.name

      this.passwordInput =
        ''

      this.showPasswordDialog =
        true

      return
    }

    void this.walkieClient
      .joinChannel(
        channel.name
      )
  }

  // ============================================================
  // 加入私密频道
  // ============================================================

  private async joinPrivateChannel():
    Promise<void> {

    const password: string =
      this.passwordInput
        .trim()

    if (
      password.length === 0
    ) {

      return
    }

    await this.walkieClient
      .joinChannel(
        this.passwordChannel,
        password
      )

    this.resetPasswordDialog()
  }

  // ============================================================
  // 重置密码弹窗
  // ============================================================

  private resetPasswordDialog(): void {

    this.showPasswordDialog =
      false

    this.passwordChannel =
      ''

    this.passwordInput =
      ''
  }

  // ============================================================
  // 删除当前频道
  // ============================================================

  private async deleteCurrentChannel():
    Promise<void> {

    if (
      this.currentChannel ===
        'public'
    ) {

      this.showDeleteDialog =
        false

      return
    }

    await this.walkieClient
      .deleteChannel(
        this.currentChannel
      )

    this.showDeleteDialog =
      false
  }

  // ============================================================
  // 网络摘要
  // ============================================================

  private buildNetworkSummary():
    string {

    const latencyText: string =
      this.networkLatency >= 0
        ? `${this.networkLatency}ms`
        : '--'

    const lossText: string =
      `${this.networkLoss.toFixed(1)}%`

    const jitterText: string =
      this.networkJitter >= 0
        ? `${this.networkJitter}ms`
        : '--'

    return (
      `${this.networkType} · ` +
        `${this.networkQuality} · ` +
        `延迟 ${latencyText} · ` +
        `丢包 ${lossText} · ` +
        `抖动 ${jitterText}`
    )
  }

  // ============================================================
  // 应用 WalkieClient 状态
  // ============================================================

  private applyClientState(
    state: WalkieClientState
  ): void {

    /*
     * UI 状态全部以 WalkieClient 为准。
     */

    this.connected =
      state.connected

    if (
      state.nickname.length > 0 &&
        state.nickname.indexOf('USER-') !== 0
    ) {

      this.nickname =
        state.nickname
    }

    this.myUserId =
      state.userId

    this.currentChannel =
      state.currentChannel

    this.onlineUsers =
      state.onlineUsers

    /*
     * 在线人数也只统计其他用户。
     */

    this.currentOnlineCount =
      state.onlineUsers.length

    this.channels =
      state.channels

    this.currentPrivate =
      state.currentChannelPrivate

    this.networkLatency =
      state.network.latency

    this.networkLoss =
      state.network.loss

    this.networkQuality =
      state.network.quality

    this.networkJitter =
      state.network.jitter

    this.talkStatus =
      state.talkStatus

    if (
      state.message.length > 0
    ) {

      this.channelMessage =
        state.message
    }

    /*
     * 网络类型目前 UDP 层没有直接返回。
     * 先根据连接状态显示。
     *
     * 后面接 HarmonyOS 网络能力 API。
     */

    if (
      this.connected
    ) {

      this.networkType =
        '公网 UDP'

    } else {

      this.networkType =
        '离线'
    }

    /*
     * ============================================================
     * 频道人数
     *
     * Android / HarmonyOS 在线人员页面只显示
     * 当前频道的“其他用户”。
     *
     * 所以这里不能再使用服务器返回的
     * channel.onlineCount，否则会把自己重新算进去。
     * ============================================================
     */

    for (
      let index = 0;
      index < this.channels.length;
      index++
    ) {

      const channel:
        WalkieChannel =
        this.channels[index]

      if (
        channel.name ===
          this.currentChannel
      ) {

        this.currentOnlineCount =
          this.onlineUsers.length

        break
      }
    }
  }
}