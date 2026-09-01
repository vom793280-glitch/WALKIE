if (!("finalizeConstruction" in ViewPU.prototype)) {
    Reflect.set(ViewPU.prototype, "finalizeConstruction", () => { });
}
interface Index_Params {
    walkieClient?: WalkieClient;
    walkieTone?: WalkieTone;
    connected?: boolean;
    nickname?: string;
    myUserId?: string;
    currentChannel?: string;
    currentOnlineCount?: number;
    currentPrivate?: boolean;
    onlineUsers?: WalkieUser[];
    channels?: WalkieChannel[];
    networkLatency?: number;
    networkLoss?: number;
    networkQuality?: string;
    networkJitter?: number;
    networkType?: string;
    talkStatus?: string;
    pressing?: boolean;
    currentPage?: string;
    channelMessage?: string;
    showNicknameDialog?: boolean;
    nicknameInput?: string;
    showCreateChannelDialog?: boolean;
    createChannelName?: string;
    createChannelPrivate?: boolean;
    createChannelPassword?: string;
    showPasswordDialog?: boolean;
    passwordChannel?: string;
    passwordInput?: string;
    showDeleteDialog?: boolean;
}
import { WalkieClient } from "@normalized:N&&&entry/src/main/ets/WalkieClient&";
import type { WalkieClientState, WalkieChannel, WalkieUser } from "@normalized:N&&&entry/src/main/ets/WalkieClient&";
import { WalkieTone } from "@normalized:N&&&entry/src/main/ets/WalkieTone&";
class Index extends ViewPU {
    constructor(parent, params, __localStorage, elmtId = -1, paramsLambda = undefined, extraInfo) {
        super(parent, __localStorage, elmtId, extraInfo);
        if (typeof paramsLambda === "function") {
            this.paramsGenerator_ = paramsLambda;
        }
        this.walkieClient = new WalkieClient();
        this.walkieTone = new WalkieTone();
        this.__connected = new ObservedPropertySimplePU(false, this, "connected");
        this.__nickname = new ObservedPropertySimplePU('', this, "nickname");
        this.__myUserId = new ObservedPropertySimplePU('', this, "myUserId");
        this.__currentChannel = new ObservedPropertySimplePU('public', this, "currentChannel");
        this.__currentOnlineCount = new ObservedPropertySimplePU(0, this, "currentOnlineCount");
        this.__currentPrivate = new ObservedPropertySimplePU(false
        // ============================================================
        // 在线人员
        // ============================================================
        , this, "currentPrivate");
        this.__onlineUsers = new ObservedPropertyObjectPU([]
        // ============================================================
        // 频道
        // ============================================================
        , this, "onlineUsers");
        this.__channels = new ObservedPropertyObjectPU([
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
        , this, "channels");
        this.__networkLatency = new ObservedPropertySimplePU(-1, this, "networkLatency");
        this.__networkLoss = new ObservedPropertySimplePU(100, this, "networkLoss");
        this.__networkQuality = new ObservedPropertySimplePU('检测中', this, "networkQuality");
        this.__networkJitter = new ObservedPropertySimplePU(-1, this, "networkJitter");
        this.__networkType = new ObservedPropertySimplePU('检测中'
        // ============================================================
        // PTT
        // ============================================================
        , this, "networkType");
        this.__talkStatus = new ObservedPropertySimplePU('NONE', this, "talkStatus");
        this.__pressing = new ObservedPropertySimplePU(false
        // ============================================================
        // 页面
        // ============================================================
        , this, "pressing");
        this.__currentPage = new ObservedPropertySimplePU('home'
        // ============================================================
        // 消息
        // ============================================================
        , this, "currentPage");
        this.__channelMessage = new ObservedPropertySimplePU(''
        // ============================================================
        // 昵称
        // ============================================================
        , this, "channelMessage");
        this.__showNicknameDialog = new ObservedPropertySimplePU(false, this, "showNicknameDialog");
        this.__nicknameInput = new ObservedPropertySimplePU(''
        // ============================================================
        // 创建频道
        // ============================================================
        , this, "nicknameInput");
        this.__showCreateChannelDialog = new ObservedPropertySimplePU(false, this, "showCreateChannelDialog");
        this.__createChannelName = new ObservedPropertySimplePU('', this, "createChannelName");
        this.__createChannelPrivate = new ObservedPropertySimplePU(false, this, "createChannelPrivate");
        this.__createChannelPassword = new ObservedPropertySimplePU(''
        // ============================================================
        // 加入私密频道
        // ============================================================
        , this, "createChannelPassword");
        this.__showPasswordDialog = new ObservedPropertySimplePU(false, this, "showPasswordDialog");
        this.__passwordChannel = new ObservedPropertySimplePU('', this, "passwordChannel");
        this.__passwordInput = new ObservedPropertySimplePU(''
        // ============================================================
        // 删除频道
        // ============================================================
        , this, "passwordInput");
        this.__showDeleteDialog = new ObservedPropertySimplePU(false
        // ============================================================
        // 生命周期
        // ============================================================
        , this, "showDeleteDialog");
        this.setInitiallyProvidedValue(params);
        this.finalizeConstruction();
    }
    setInitiallyProvidedValue(params: Index_Params) {
        if (params.walkieClient !== undefined) {
            this.walkieClient = params.walkieClient;
        }
        if (params.walkieTone !== undefined) {
            this.walkieTone = params.walkieTone;
        }
        if (params.connected !== undefined) {
            this.connected = params.connected;
        }
        if (params.nickname !== undefined) {
            this.nickname = params.nickname;
        }
        if (params.myUserId !== undefined) {
            this.myUserId = params.myUserId;
        }
        if (params.currentChannel !== undefined) {
            this.currentChannel = params.currentChannel;
        }
        if (params.currentOnlineCount !== undefined) {
            this.currentOnlineCount = params.currentOnlineCount;
        }
        if (params.currentPrivate !== undefined) {
            this.currentPrivate = params.currentPrivate;
        }
        if (params.onlineUsers !== undefined) {
            this.onlineUsers = params.onlineUsers;
        }
        if (params.channels !== undefined) {
            this.channels = params.channels;
        }
        if (params.networkLatency !== undefined) {
            this.networkLatency = params.networkLatency;
        }
        if (params.networkLoss !== undefined) {
            this.networkLoss = params.networkLoss;
        }
        if (params.networkQuality !== undefined) {
            this.networkQuality = params.networkQuality;
        }
        if (params.networkJitter !== undefined) {
            this.networkJitter = params.networkJitter;
        }
        if (params.networkType !== undefined) {
            this.networkType = params.networkType;
        }
        if (params.talkStatus !== undefined) {
            this.talkStatus = params.talkStatus;
        }
        if (params.pressing !== undefined) {
            this.pressing = params.pressing;
        }
        if (params.currentPage !== undefined) {
            this.currentPage = params.currentPage;
        }
        if (params.channelMessage !== undefined) {
            this.channelMessage = params.channelMessage;
        }
        if (params.showNicknameDialog !== undefined) {
            this.showNicknameDialog = params.showNicknameDialog;
        }
        if (params.nicknameInput !== undefined) {
            this.nicknameInput = params.nicknameInput;
        }
        if (params.showCreateChannelDialog !== undefined) {
            this.showCreateChannelDialog = params.showCreateChannelDialog;
        }
        if (params.createChannelName !== undefined) {
            this.createChannelName = params.createChannelName;
        }
        if (params.createChannelPrivate !== undefined) {
            this.createChannelPrivate = params.createChannelPrivate;
        }
        if (params.createChannelPassword !== undefined) {
            this.createChannelPassword = params.createChannelPassword;
        }
        if (params.showPasswordDialog !== undefined) {
            this.showPasswordDialog = params.showPasswordDialog;
        }
        if (params.passwordChannel !== undefined) {
            this.passwordChannel = params.passwordChannel;
        }
        if (params.passwordInput !== undefined) {
            this.passwordInput = params.passwordInput;
        }
        if (params.showDeleteDialog !== undefined) {
            this.showDeleteDialog = params.showDeleteDialog;
        }
    }
    updateStateVars(params: Index_Params) {
    }
    purgeVariableDependenciesOnElmtId(rmElmtId) {
        this.__connected.purgeDependencyOnElmtId(rmElmtId);
        this.__nickname.purgeDependencyOnElmtId(rmElmtId);
        this.__myUserId.purgeDependencyOnElmtId(rmElmtId);
        this.__currentChannel.purgeDependencyOnElmtId(rmElmtId);
        this.__currentOnlineCount.purgeDependencyOnElmtId(rmElmtId);
        this.__currentPrivate.purgeDependencyOnElmtId(rmElmtId);
        this.__onlineUsers.purgeDependencyOnElmtId(rmElmtId);
        this.__channels.purgeDependencyOnElmtId(rmElmtId);
        this.__networkLatency.purgeDependencyOnElmtId(rmElmtId);
        this.__networkLoss.purgeDependencyOnElmtId(rmElmtId);
        this.__networkQuality.purgeDependencyOnElmtId(rmElmtId);
        this.__networkJitter.purgeDependencyOnElmtId(rmElmtId);
        this.__networkType.purgeDependencyOnElmtId(rmElmtId);
        this.__talkStatus.purgeDependencyOnElmtId(rmElmtId);
        this.__pressing.purgeDependencyOnElmtId(rmElmtId);
        this.__currentPage.purgeDependencyOnElmtId(rmElmtId);
        this.__channelMessage.purgeDependencyOnElmtId(rmElmtId);
        this.__showNicknameDialog.purgeDependencyOnElmtId(rmElmtId);
        this.__nicknameInput.purgeDependencyOnElmtId(rmElmtId);
        this.__showCreateChannelDialog.purgeDependencyOnElmtId(rmElmtId);
        this.__createChannelName.purgeDependencyOnElmtId(rmElmtId);
        this.__createChannelPrivate.purgeDependencyOnElmtId(rmElmtId);
        this.__createChannelPassword.purgeDependencyOnElmtId(rmElmtId);
        this.__showPasswordDialog.purgeDependencyOnElmtId(rmElmtId);
        this.__passwordChannel.purgeDependencyOnElmtId(rmElmtId);
        this.__passwordInput.purgeDependencyOnElmtId(rmElmtId);
        this.__showDeleteDialog.purgeDependencyOnElmtId(rmElmtId);
    }
    aboutToBeDeleted() {
        this.__connected.aboutToBeDeleted();
        this.__nickname.aboutToBeDeleted();
        this.__myUserId.aboutToBeDeleted();
        this.__currentChannel.aboutToBeDeleted();
        this.__currentOnlineCount.aboutToBeDeleted();
        this.__currentPrivate.aboutToBeDeleted();
        this.__onlineUsers.aboutToBeDeleted();
        this.__channels.aboutToBeDeleted();
        this.__networkLatency.aboutToBeDeleted();
        this.__networkLoss.aboutToBeDeleted();
        this.__networkQuality.aboutToBeDeleted();
        this.__networkJitter.aboutToBeDeleted();
        this.__networkType.aboutToBeDeleted();
        this.__talkStatus.aboutToBeDeleted();
        this.__pressing.aboutToBeDeleted();
        this.__currentPage.aboutToBeDeleted();
        this.__channelMessage.aboutToBeDeleted();
        this.__showNicknameDialog.aboutToBeDeleted();
        this.__nicknameInput.aboutToBeDeleted();
        this.__showCreateChannelDialog.aboutToBeDeleted();
        this.__createChannelName.aboutToBeDeleted();
        this.__createChannelPrivate.aboutToBeDeleted();
        this.__createChannelPassword.aboutToBeDeleted();
        this.__showPasswordDialog.aboutToBeDeleted();
        this.__passwordChannel.aboutToBeDeleted();
        this.__passwordInput.aboutToBeDeleted();
        this.__showDeleteDialog.aboutToBeDeleted();
        SubscriberManager.Get().delete(this.id__());
        this.aboutToBeDeletedInternal();
    }
    // ============================================================
    // WALKIE 客户端
    // ============================================================
    private walkieClient: WalkieClient;
    private walkieTone: WalkieTone;
    // ============================================================
    // 首页状态
    // ============================================================
    private __connected: ObservedPropertySimplePU<boolean>;
    get connected() {
        return this.__connected.get();
    }
    set connected(newValue: boolean) {
        this.__connected.set(newValue);
    }
    private __nickname: ObservedPropertySimplePU<string>;
    get nickname() {
        return this.__nickname.get();
    }
    set nickname(newValue: string) {
        this.__nickname.set(newValue);
    }
    private __myUserId: ObservedPropertySimplePU<string>;
    get myUserId() {
        return this.__myUserId.get();
    }
    set myUserId(newValue: string) {
        this.__myUserId.set(newValue);
    }
    private __currentChannel: ObservedPropertySimplePU<string>;
    get currentChannel() {
        return this.__currentChannel.get();
    }
    set currentChannel(newValue: string) {
        this.__currentChannel.set(newValue);
    }
    private __currentOnlineCount: ObservedPropertySimplePU<number>;
    get currentOnlineCount() {
        return this.__currentOnlineCount.get();
    }
    set currentOnlineCount(newValue: number) {
        this.__currentOnlineCount.set(newValue);
    }
    private __currentPrivate: ObservedPropertySimplePU<boolean>;
    get currentPrivate() {
        return this.__currentPrivate.get();
    }
    set currentPrivate(newValue: boolean) {
        this.__currentPrivate.set(newValue);
    }
    // ============================================================
    // 在线人员
    // ============================================================
    private __onlineUsers: ObservedPropertyObjectPU<WalkieUser[]>;
    get onlineUsers() {
        return this.__onlineUsers.get();
    }
    set onlineUsers(newValue: WalkieUser[]) {
        this.__onlineUsers.set(newValue);
    }
    // ============================================================
    // 频道
    // ============================================================
    private __channels: ObservedPropertyObjectPU<WalkieChannel[]>;
    get channels() {
        return this.__channels.get();
    }
    set channels(newValue: WalkieChannel[]) {
        this.__channels.set(newValue);
    }
    // ============================================================
    // 网络
    // ============================================================
    private __networkLatency: ObservedPropertySimplePU<number>;
    get networkLatency() {
        return this.__networkLatency.get();
    }
    set networkLatency(newValue: number) {
        this.__networkLatency.set(newValue);
    }
    private __networkLoss: ObservedPropertySimplePU<number>;
    get networkLoss() {
        return this.__networkLoss.get();
    }
    set networkLoss(newValue: number) {
        this.__networkLoss.set(newValue);
    }
    private __networkQuality: ObservedPropertySimplePU<string>;
    get networkQuality() {
        return this.__networkQuality.get();
    }
    set networkQuality(newValue: string) {
        this.__networkQuality.set(newValue);
    }
    private __networkJitter: ObservedPropertySimplePU<number>;
    get networkJitter() {
        return this.__networkJitter.get();
    }
    set networkJitter(newValue: number) {
        this.__networkJitter.set(newValue);
    }
    private __networkType: ObservedPropertySimplePU<string>;
    get networkType() {
        return this.__networkType.get();
    }
    set networkType(newValue: string) {
        this.__networkType.set(newValue);
    }
    // ============================================================
    // PTT
    // ============================================================
    private __talkStatus: ObservedPropertySimplePU<string>;
    get talkStatus() {
        return this.__talkStatus.get();
    }
    set talkStatus(newValue: string) {
        this.__talkStatus.set(newValue);
    }
    private __pressing: ObservedPropertySimplePU<boolean>;
    get pressing() {
        return this.__pressing.get();
    }
    set pressing(newValue: boolean) {
        this.__pressing.set(newValue);
    }
    // ============================================================
    // 页面
    // ============================================================
    private __currentPage: ObservedPropertySimplePU<string>;
    get currentPage() {
        return this.__currentPage.get();
    }
    set currentPage(newValue: string) {
        this.__currentPage.set(newValue);
    }
    // ============================================================
    // 消息
    // ============================================================
    private __channelMessage: ObservedPropertySimplePU<string>;
    get channelMessage() {
        return this.__channelMessage.get();
    }
    set channelMessage(newValue: string) {
        this.__channelMessage.set(newValue);
    }
    // ============================================================
    // 昵称
    // ============================================================
    private __showNicknameDialog: ObservedPropertySimplePU<boolean>;
    get showNicknameDialog() {
        return this.__showNicknameDialog.get();
    }
    set showNicknameDialog(newValue: boolean) {
        this.__showNicknameDialog.set(newValue);
    }
    private __nicknameInput: ObservedPropertySimplePU<string>;
    get nicknameInput() {
        return this.__nicknameInput.get();
    }
    set nicknameInput(newValue: string) {
        this.__nicknameInput.set(newValue);
    }
    // ============================================================
    // 创建频道
    // ============================================================
    private __showCreateChannelDialog: ObservedPropertySimplePU<boolean>;
    get showCreateChannelDialog() {
        return this.__showCreateChannelDialog.get();
    }
    set showCreateChannelDialog(newValue: boolean) {
        this.__showCreateChannelDialog.set(newValue);
    }
    private __createChannelName: ObservedPropertySimplePU<string>;
    get createChannelName() {
        return this.__createChannelName.get();
    }
    set createChannelName(newValue: string) {
        this.__createChannelName.set(newValue);
    }
    private __createChannelPrivate: ObservedPropertySimplePU<boolean>;
    get createChannelPrivate() {
        return this.__createChannelPrivate.get();
    }
    set createChannelPrivate(newValue: boolean) {
        this.__createChannelPrivate.set(newValue);
    }
    private __createChannelPassword: ObservedPropertySimplePU<string>;
    get createChannelPassword() {
        return this.__createChannelPassword.get();
    }
    set createChannelPassword(newValue: string) {
        this.__createChannelPassword.set(newValue);
    }
    // ============================================================
    // 加入私密频道
    // ============================================================
    private __showPasswordDialog: ObservedPropertySimplePU<boolean>;
    get showPasswordDialog() {
        return this.__showPasswordDialog.get();
    }
    set showPasswordDialog(newValue: boolean) {
        this.__showPasswordDialog.set(newValue);
    }
    private __passwordChannel: ObservedPropertySimplePU<string>;
    get passwordChannel() {
        return this.__passwordChannel.get();
    }
    set passwordChannel(newValue: string) {
        this.__passwordChannel.set(newValue);
    }
    private __passwordInput: ObservedPropertySimplePU<string>;
    get passwordInput() {
        return this.__passwordInput.get();
    }
    set passwordInput(newValue: string) {
        this.__passwordInput.set(newValue);
    }
    // ============================================================
    // 删除频道
    // ============================================================
    private __showDeleteDialog: ObservedPropertySimplePU<boolean>;
    get showDeleteDialog() {
        return this.__showDeleteDialog.get();
    }
    set showDeleteDialog(newValue: boolean) {
        this.__showDeleteDialog.set(newValue);
    }
    // ============================================================
    // 生命周期
    // ============================================================
    aboutToAppear(): void {
        const hostContext = this.getUIContext()
            .getHostContext();
        /*
         * 注册 WalkieClient 状态回调。
         *
         * WalkieClient 收到 VPS 数据后，
         * 会统一从这里更新界面。
         */
        this.walkieClient.setStateCallback((state: WalkieClientState): void => {
            this.applyClientState(state);
        });
    }
    aboutToDisappear(): void {
        /*
         * 页面退出时关闭 UDP。
         */
        void this.walkieClient.close();
    }
    // ============================================================
    // 系统返回 / 侧滑返回
    // ============================================================
    onBackPress(): boolean {
        if (this.currentPage !== 'home') {
            this.currentPage =
                'home';
            return true;
        }
        return false;
    }
    // ============================================================
    // 主页面
    // ============================================================
    initialRender() {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Stack.create();
            Stack.width('100%');
            Stack.height('100%');
        }, Stack);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
            Column.backgroundColor('#F6F7F9');
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.currentPage ===
                'home') {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.homePage.bind(this)();
                });
            }
            else if (this.currentPage ===
                'users') {
                this.ifElseBranchUpdateFunction(1, () => {
                    this.usersPage.bind(this)();
                });
            }
            else if (this.currentPage ===
                'channels') {
                this.ifElseBranchUpdateFunction(2, () => {
                    this.channelsPage.bind(this)();
                });
            }
            else if (this.currentPage ===
                'settings') {
                this.ifElseBranchUpdateFunction(3, () => {
                    this.settingsPage.bind(this)();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(4, () => {
                });
            }
        }, If);
        If.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.showNicknameDialog) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.nicknameDialog.bind(this)();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                });
            }
        }, If);
        If.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.showCreateChannelDialog) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.createChannelDialog.bind(this)();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                });
            }
        }, If);
        If.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.showPasswordDialog) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.passwordDialog.bind(this)();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                });
            }
        }, If);
        If.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.showDeleteDialog) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.deleteChannelDialog.bind(this)();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                });
            }
        }, If);
        If.pop();
        Stack.pop();
    }
    // ============================================================
    // 首页
    // ============================================================
    homePage(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            // --------------------------------------------------------
            // 顶部
            // --------------------------------------------------------
            Row.create();
            // --------------------------------------------------------
            // 顶部
            // --------------------------------------------------------
            Row.width('100%');
            // --------------------------------------------------------
            // 顶部
            // --------------------------------------------------------
            Row.padding({
                left: 16,
                right: 16,
                top: 18,
                bottom: 10
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('兄弟对讲机');
            Text.fontSize(28);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#181B1F');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('V23.3');
            Text.fontSize(13);
            Text.fontColor('#777E87');
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.statusPill.bind(this)();
        // --------------------------------------------------------
        // 顶部
        // --------------------------------------------------------
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            // --------------------------------------------------------
            // 主体
            // --------------------------------------------------------
            Scroll.create();
            // --------------------------------------------------------
            // 主体
            // --------------------------------------------------------
            Scroll.layoutWeight(1);
        }, Scroll);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.padding({
                left: 15,
                right: 15,
                bottom: 10
            });
        }, Column);
        this.nicknameCard.bind(this)();
        this.serverCard.bind(this)();
        this.channelCard.bind(this)();
        this.networkCard.bind(this)();
        this.onlineUsersCard.bind(this)();
        this.bottomButtons.bind(this)();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Blank.create();
            Blank.height(110);
        }, Blank);
        Blank.pop();
        Column.pop();
        // --------------------------------------------------------
        // 主体
        // --------------------------------------------------------
        Scroll.pop();
        // --------------------------------------------------------
        // PTT
        // --------------------------------------------------------
        this.pttBar.bind(this)();
        Column.pop();
    }
    // ============================================================
    // 在线 / 离线
    // ============================================================
    statusPill(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.padding({
                left: 11,
                right: 11,
                top: 7,
                bottom: 7
            });
            Row.backgroundColor('#FFFFFF');
            Row.borderRadius(20);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Circle.create();
            Circle.width(9);
            Circle.height(9);
            Circle.fill(this.connected
                ? '#35C759'
                : '#FF3B30');
        }, Circle);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.connected
                ? '在线'
                : '离线');
            Text.fontSize(13);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#4E555C');
            Text.margin({
                left: 6
            });
        }, Text);
        Text.pop();
        Row.pop();
    }
    // ============================================================
    // 昵称
    // ============================================================
    nicknameCard(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding(16);
            Row.backgroundColor('#FFFFFF');
            Row.borderRadius(18);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Stack.create();
        }, Stack);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Circle.create();
            Circle.width(54);
            Circle.height(54);
            Circle.fill('#E8EDF6');
        }, Circle);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('👤');
            Text.fontSize(25);
        }, Text);
        Text.pop();
        Stack.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.layoutWeight(1);
            Column.margin({
                left: 12
            });
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('我的昵称');
            Text.fontSize(12);
            Text.fontColor('#858B93');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.nickname.length > 0
                ? this.nickname
                : '未设置昵称');
            Text.fontSize(20);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#181B1F');
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('修改');
            Button.height(38);
            Button.fontSize(13);
            Button.fontColor('#3C6FF0');
            Button.backgroundColor('#EDF2FF');
            Button.borderRadius(19);
            Button.onClick(() => {
                this.nicknameInput =
                    this.nickname;
                this.showNicknameDialog =
                    true;
            });
        }, Button);
        Button.pop();
        Row.pop();
    }
    // ============================================================
    // 服务器
    // ============================================================
    serverCard(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding(16);
            Row.margin({
                top: 9
            });
            Row.backgroundColor(this.connected
                ? '#EEF5FF'
                : '#FFFFFF');
            Row.borderRadius(18);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('兄弟服务器');
            Text.fontSize(12);
            Text.fontColor('#858B93');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.connected
                ? '已连接'
                : '未连接');
            Text.fontSize(20);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#181B1F');
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.connected
                ? this.networkType
                : '服务器地址已隐藏');
            Text.fontSize(12);
            Text.fontColor('#858B93');
            Text.margin({
                top: 4
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.connected) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        Button.createWithLabel('断开');
                        Button.height(40);
                        Button.fontSize(14);
                        Button.fontColor('#D32F2F');
                        Button.backgroundColor('#FFF0F0');
                        Button.borderRadius(20);
                        Button.onClick(() => {
                            void this.disconnectServer();
                        });
                    }, Button);
                    Button.pop();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        Button.createWithLabel('连接');
                        Button.height(40);
                        Button.fontSize(14);
                        Button.fontColor('#FFFFFF');
                        Button.backgroundColor('#3C6FF0');
                        Button.borderRadius(20);
                        Button.onClick(() => {
                            void this.connectServer();
                        });
                    }, Button);
                    Button.pop();
                });
            }
        }, If);
        If.pop();
        Row.pop();
    }
    // ============================================================
    // 当前频道
    // ============================================================
    channelCard(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.padding(16);
            Column.margin({
                top: 9
            });
            Column.backgroundColor('#FFFFFF');
            Column.borderRadius(18);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('当前频道');
            Text.fontSize(12);
            Text.fontColor('#858B93');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.currentPrivate
                ? `🔒 ${this.currentChannel}`
                : `🌐 ${this.currentChannel}`);
            Text.fontSize(23);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#181B1F');
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(`在线人数：${this.currentOnlineCount} 人`);
            Text.fontSize(13);
            Text.fontColor('#858B93');
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('管理');
            Button.height(38);
            Button.fontSize(13);
            Button.fontColor('#3C6FF0');
            Button.backgroundColor('#EDF2FF');
            Button.borderRadius(19);
            Button.enabled(this.connected);
            Button.onClick(() => {
                this.currentPage =
                    'channels';
            });
        }, Button);
        Button.pop();
        Row.pop();
        Column.pop();
    }
    // ============================================================
    // 网络
    // ============================================================
    networkCard(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding(13);
            Row.margin({
                top: 9
            });
            Row.backgroundColor('#FFFFFF');
            Row.borderRadius(18);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Stack.create();
        }, Stack);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Circle.create();
            Circle.width(42);
            Circle.height(42);
            Circle.fill('#E9EEF9');
        }, Circle);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('📶');
            Text.fontSize(19);
        }, Text);
        Text.pop();
        Stack.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.margin({
                left: 10
            });
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('网络状态');
            Text.fontSize(15);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#181B1F');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.buildNetworkSummary());
            Text.fontSize(11);
            Text.fontColor('#858B93');
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.End);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.networkLatency >= 0
                ? `${this.networkLatency} ms`
                : '--');
            Text.fontSize(15);
            Text.fontWeight(FontWeight.Bold);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('延迟');
            Text.fontSize(10);
            Text.fontColor('#858B93');
            Text.margin({
                top: 2
            });
        }, Text);
        Text.pop();
        Column.pop();
        Row.pop();
    }
    // ============================================================
    // 在线人员
    // ============================================================
    onlineUsersCard(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.padding(13);
            Column.margin({
                top: 9
            });
            Column.backgroundColor('#FFFFFF');
            Column.borderRadius(18);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('在线人员');
            Text.fontSize(16);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#181B1F');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.connected
                ? '当前频道成员'
                : '连接服务器后显示');
            Text.fontSize(11);
            Text.fontColor('#858B93');
            Text.margin({
                top: 2
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('全部');
            Button.height(34);
            Button.fontSize(12);
            Button.fontColor('#3C6FF0');
            Button.backgroundColor('#EDF2FF');
            Button.borderRadius(17);
            Button.enabled(this.connected);
            Button.onClick(() => {
                this.currentPage =
                    'users';
            });
        }, Button);
        Button.pop();
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (!this.connected) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.emptyMiniCard.bind(this)('当前未连接服务器');
                });
            }
            else if (this.onlineUsers.length === 0) {
                this.ifElseBranchUpdateFunction(1, () => {
                    this.emptyMiniCard.bind(this)('正在同步在线人员…');
                });
            }
            else {
                this.ifElseBranchUpdateFunction(2, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        ForEach.create();
                        const forEachItemGenFunction = _item => {
                            const user = _item;
                            this.userRow.bind(this)(user);
                        };
                        this.forEachUpdateFunction(elmtId, this.onlineUsers.slice(0, 5), forEachItemGenFunction, (user: WalkieUser) => {
                            return user.userId;
                        }, false, false);
                    }, ForEach);
                    ForEach.pop();
                });
            }
        }, If);
        If.pop();
        Column.pop();
    }
    // ============================================================
    // 用户行
    // ============================================================
    userRow(user: WalkieUser, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.height(40);
            Row.padding({
                left: 7,
                right: 7
            });
            Row.margin({
                top: 5
            });
            Row.backgroundColor('#F5F7FA');
            Row.borderRadius(11);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Circle.create();
            Circle.width(30);
            Circle.height(30);
            Circle.fill('#E8EDF6');
        }, Circle);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('👤');
            Text.fontSize(14);
            Text.position({
                x: 7,
                y: 4
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(user.nickname.length > 0
                ? user.nickname
                : '未命名用户');
            Text.fontSize(14);
            Text.fontWeight(FontWeight.Medium);
            Text.margin({
                left: 8
            });
            Text.layoutWeight(1);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('🟢');
            Text.fontSize(10);
        }, Text);
        Text.pop();
        Row.pop();
    }
    // ============================================================
    // 空小卡片
    // ============================================================
    emptyMiniCard(text: string, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.height(42);
            Row.justifyContent(FlexAlign.Center);
            Row.backgroundColor('#F7F8FA');
            Row.borderRadius(11);
            Row.margin({
                top: 7
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(text);
            Text.fontSize(11);
            Text.fontColor('#858B93');
        }, Text);
        Text.pop();
        Row.pop();
    }
    // ============================================================
    // 底部按钮
    // ============================================================
    bottomButtons(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.margin({
                top: 10
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('频道');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                right: 5
            });
            Button.fontSize(14);
            Button.fontColor('#3B4148');
            Button.backgroundColor('#FFFFFF');
            Button.borderRadius(14);
            Button.onClick(() => {
                this.currentPage =
                    'channels';
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('设置');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                left: 5
            });
            Button.fontSize(14);
            Button.fontColor('#FFFFFF');
            Button.backgroundColor('#3C6FF0');
            Button.borderRadius(14);
            Button.onClick(() => {
                this.currentPage =
                    'settings';
            });
        }, Button);
        Button.pop();
        Row.pop();
    }
    // ============================================================
    // PTT
    // ============================================================
    pttBar(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.padding({
                left: 15,
                right: 15,
                top: 8,
                bottom: 10
            });
            Column.backgroundColor('#FFFFFF');
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.getTalkStatusText());
            Text.fontSize(13);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#33383E');
            Text.margin({
                bottom: 6
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.height(72);
            Row.justifyContent(FlexAlign.Center);
            Row.backgroundColor(this.getPttColor());
            Row.borderRadius(21);
            Row.onTouch((event: TouchEvent) => {
                if (!this.connected) {
                    return;
                }
                if (event.type ===
                    TouchType.Down) {
                    this.pressing =
                        true;
                    void this.walkieClient
                        .startTalking();
                }
                if (event.type ===
                    TouchType.Up ||
                    event.type ===
                        TouchType.Cancel) {
                    this.pressing =
                        false;
                    void this.walkieClient
                        .stopTalking();
                }
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.connected
                ? '🎙'
                : '🔇');
            Text.fontSize(29);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.getPttButtonText());
            Text.fontSize(21);
            Text.fontWeight(FontWeight.Bold);
            Text.fontColor('#FFFFFF');
            Text.margin({
                left: 11
            });
        }, Text);
        Text.pop();
        Row.pop();
        Column.pop();
    }
    // ============================================================
    // PTT 状态文字
    // ============================================================
    getTalkStatusText(): string {
        if (!this.connected) {
            return '连接服务器后即可讲话';
        }
        if (this.talkStatus ===
            'ALLOWED') {
            return '正在讲话 · 松开结束';
        }
        if (this.talkStatus ===
            'REQUESTING') {
            return '正在抢麦…请保持按住';
        }
        if (this.talkStatus ===
            'BUSY') {
            return '频道正在通话';
        }
        if (this.pressing) {
            return '讲话中';
        }
        return '按住说话';
    }
    // ============================================================
    // PTT 按钮文字
    // ============================================================
    getPttButtonText(): string {
        if (!this.connected) {
            return '连接后使用';
        }
        if (this.talkStatus ===
            'ALLOWED') {
            return '松开结束讲话';
        }
        if (this.talkStatus ===
            'REQUESTING') {
            return '抢麦中 · 不要松开';
        }
        if (this.talkStatus ===
            'BUSY') {
            return '忙线';
        }
        return '按住说话';
    }
    // ============================================================
    // PTT 颜色
    // ============================================================
    getPttColor(): string {
        if (!this.connected) {
            return '#9E9E9E';
        }
        if (this.talkStatus ===
            'ALLOWED') {
            return '#D32F2F';
        }
        if (this.talkStatus ===
            'REQUESTING') {
            return '#F57C00';
        }
        if (this.talkStatus ===
            'BUSY') {
            return '#616161';
        }
        if (this.pressing) {
            return '#C62828';
        }
        return '#3C6FF0';
    }
    // ============================================================
    // 在线人员页面
    // ============================================================
    usersPage(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
        }, Column);
        this.pageHeader.bind(this)('在线人员', `频道：${this.currentChannel}`);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Scroll.create();
            Scroll.layoutWeight(1);
        }, Scroll);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.padding(15);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding(15);
            Row.backgroundColor('#FFFFFF');
            Row.borderRadius(17);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('当前在线');
            Text.fontSize(11);
            Text.fontColor('#858B93');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.connected
                ? `${this.currentOnlineCount} 人`
                : '未连接');
            Text.fontSize(21);
            Text.fontWeight(FontWeight.Bold);
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('👥');
            Text.fontSize(27);
        }, Text);
        Text.pop();
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (!this.connected) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.emptyCard.bind(this)('当前未连接服务器');
                });
            }
            else if (this.onlineUsers.length === 0) {
                this.ifElseBranchUpdateFunction(1, () => {
                    this.emptyCard.bind(this)('正在同步在线人员…');
                });
            }
            else {
                this.ifElseBranchUpdateFunction(2, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        ForEach.create();
                        const forEachItemGenFunction = _item => {
                            const user = _item;
                            this.largeUserRow.bind(this)(user);
                        };
                        this.forEachUpdateFunction(elmtId, this.onlineUsers, forEachItemGenFunction, (user: WalkieUser) => {
                            return user.userId;
                        }, false, false);
                    }, ForEach);
                    ForEach.pop();
                });
            }
        }, If);
        If.pop();
        Column.pop();
        Scroll.pop();
        Column.pop();
    }
    // ============================================================
    // 用户大行
    // ============================================================
    largeUserRow(user: WalkieUser, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding(13);
            Row.margin({
                top: 7
            });
            Row.backgroundColor('#FFFFFF');
            Row.borderRadius(17);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Circle.create();
            Circle.width(43);
            Circle.height(43);
            Circle.fill('#E8EDF6');
        }, Circle);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('👤');
            Text.fontSize(20);
            Text.position({
                x: 10,
                y: 8
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.layoutWeight(1);
            Column.margin({
                left: 11
            });
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(user.nickname.length > 0
                ? user.nickname
                : '未命名用户');
            Text.fontSize(16);
            Text.fontWeight(FontWeight.Bold);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('在线');
            Text.fontSize(10);
            Text.fontColor('#858B93');
            Text.margin({
                top: 2
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('🟢');
            Text.fontSize(12);
        }, Text);
        Text.pop();
        Row.pop();
    }
    // ============================================================
    // 空卡片
    // ============================================================
    emptyCard(text: string, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.height(65);
            Row.justifyContent(FlexAlign.Center);
            Row.backgroundColor('#FFFFFF');
            Row.borderRadius(17);
            Row.margin({
                top: 9
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(text);
            Text.fontSize(13);
            Text.fontColor('#858B93');
        }, Text);
        Text.pop();
        Row.pop();
    }
    // ============================================================
    // 频道页面
    // ============================================================
    channelsPage(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
        }, Column);
        this.pageHeader.bind(this)('频道', '切换和管理频道');
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding({
                left: 15,
                right: 15,
                top: 10
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('＋ 创建');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                right: 5
            });
            Button.enabled(this.connected);
            Button.fontColor('#FFFFFF');
            Button.backgroundColor('#3C6FF0');
            Button.borderRadius(12);
            Button.onClick(() => {
                this.showCreateChannelDialog =
                    true;
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('刷新');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                left: 5
            });
            Button.enabled(this.connected);
            Button.fontColor('#3B4148');
            Button.backgroundColor('#EEF1F5');
            Button.borderRadius(12);
            Button.onClick(() => {
                void this.walkieClient
                    .requestChannelList();
            });
        }, Button);
        Button.pop();
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('删除当前频道');
            Button.width('calc(100% - 30vp)');
            Button.height(40);
            Button.margin({
                top: 8,
                left: 15,
                right: 15
            });
            Button.enabled(this.connected &&
                this.currentChannel !==
                    'public');
            Button.fontColor('#D92F2F');
            Button.backgroundColor('#FFF0F0');
            Button.borderRadius(12);
            Button.onClick(() => {
                this.showDeleteDialog =
                    true;
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Scroll.create();
            Scroll.layoutWeight(1);
        }, Scroll);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.padding(15);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            ForEach.create();
            const forEachItemGenFunction = _item => {
                const channel = _item;
                this.channelRow.bind(this)(channel);
            };
            this.forEachUpdateFunction(elmtId, this.channels, forEachItemGenFunction, (channel: WalkieChannel) => {
                return channel.name;
            }, false, false);
        }, ForEach);
        ForEach.pop();
        Column.pop();
        Scroll.pop();
        Column.pop();
    }
    // ============================================================
    // 频道行
    // ============================================================
    channelRow(channel: WalkieChannel, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding(14);
            Row.margin({
                top: 7
            });
            Row.backgroundColor('#FFFFFF');
            Row.borderRadius(16);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(channel.isPrivate
                ? `🔒 ${channel.name}`
                : `🌐 ${channel.name}`);
            Text.fontSize(17);
            Text.fontWeight(FontWeight.Bold);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(`在线 ${channel.onlineCount} 人`);
            Text.fontSize(11);
            Text.fontColor('#858B93');
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (channel.name ===
                this.currentChannel) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        Text.create('当前');
                        Text.fontSize(13);
                        Text.fontWeight(FontWeight.Bold);
                        Text.fontColor('#3C6FF0');
                    }, Text);
                    Text.pop();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        Button.createWithLabel(channel.isPrivate ||
                            channel.requirePassword
                            ? '加入'
                            : '切换');
                        Button.height(36);
                        Button.fontSize(12);
                        Button.backgroundColor('#EEF1F5');
                        Button.fontColor('#3B4148');
                        Button.borderRadius(12);
                        Button.enabled(this.connected);
                        Button.onClick(() => {
                            this.selectChannel(channel);
                        });
                    }, Button);
                    Button.pop();
                });
            }
        }, If);
        If.pop();
        Row.pop();
    }
    // ============================================================
    // 设置页面
    // ============================================================
    settingsPage(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
        }, Column);
        this.pageHeader.bind(this)('设置', '个人与应用信息');
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Scroll.create();
            Scroll.layoutWeight(1);
        }, Scroll);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.padding(15);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding(14);
            Row.backgroundColor('#FFFFFF');
            Row.borderRadius(18);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Circle.create();
            Circle.width(44);
            Circle.height(44);
            Circle.fill('#E8EDF6');
        }, Circle);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.margin({
                left: 10
            });
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('昵称');
            Text.fontSize(11);
            Text.fontColor('#858B93');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.nickname.length > 0
                ? this.nickname
                : '未设置');
            Text.fontSize(17);
            Text.fontWeight(FontWeight.Bold);
            Text.margin({
                top: 3
            });
        }, Text);
        Text.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('修改');
            Button.height(36);
            Button.fontSize(12);
            Button.backgroundColor('#EEF2FF');
            Button.fontColor('#3C6FF0');
            Button.borderRadius(18);
            Button.onClick(() => {
                this.nicknameInput =
                    this.nickname;
                this.showNicknameDialog =
                    true;
            });
        }, Button);
        Button.pop();
        Row.pop();
        this.infoCard.bind(this)('网络状态', this.buildNetworkSummary());
        this.infoCard.bind(this)('用户标识', this.myUserId.length > 0
            ? this.myUserId
            : '连接后由服务器分配');
        this.infoCard.bind(this)('服务器', '兄弟服务器');
        this.infoCard.bind(this)('版本', 'V23.3');
        Column.pop();
        Scroll.pop();
        Column.pop();
    }
    // ============================================================
    // 页面标题
    // ============================================================
    pageHeader(title: string, subtitle: string, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding({
                left: 15,
                right: 15,
                top: 16,
                bottom: 7
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('‹');
            Button.width(42);
            Button.height(42);
            Button.fontSize(27);
            Button.fontColor('#33383E');
            Button.backgroundColor('#FFFFFF');
            Button.borderRadius(21);
            Button.onClick(() => {
                this.currentPage =
                    'home';
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.margin({
                left: 9
            });
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(title);
            Text.fontSize(23);
            Text.fontWeight(FontWeight.Bold);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(subtitle);
            Text.fontSize(11);
            Text.fontColor('#858B93');
            Text.margin({
                top: 2
            });
        }, Text);
        Text.pop();
        Column.pop();
        Row.pop();
    }
    // ============================================================
    // 信息卡
    // ============================================================
    infoCard(title: string, value: string, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.padding(14);
            Column.backgroundColor('#FFFFFF');
            Column.borderRadius(17);
            Column.margin({
                top: 8
            });
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(title);
            Text.fontSize(11);
            Text.fontColor('#858B93');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(value);
            Text.fontSize(16);
            Text.fontWeight(FontWeight.Bold);
            Text.margin({
                top: 4
            });
        }, Text);
        Text.pop();
        Column.pop();
    }
    // ============================================================
    // 昵称弹窗
    // ============================================================
    nicknameDialog(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
            Column.backgroundColor('#00000066');
            Column.justifyContent(FlexAlign.Center);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('86%');
            Column.padding(20);
            Column.backgroundColor('#FFFFFF');
            Column.borderRadius(20);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.nickname.length > 0
                ? '修改昵称'
                : '首次设置昵称');
            Text.fontSize(21);
            Text.fontWeight(FontWeight.Bold);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.nickname.length > 0
                ? '修改后会同步到服务器'
                : '请输入你的对讲机昵称');
            Text.fontSize(12);
            Text.fontColor('#858B93');
            Text.margin({
                top: 5
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            TextInput.create({
                placeholder: '请输入昵称',
                text: this.nicknameInput
            });
            TextInput.width('100%');
            TextInput.height(48);
            TextInput.margin({
                top: 15
            });
            TextInput.onChange((value: string) => {
                this.nicknameInput =
                    value
                        .substring(0, 20);
            });
        }, TextInput);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.margin({
                top: 16
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('取消');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                right: 5
            });
            Button.fontColor('#41474E');
            Button.backgroundColor('#EEF1F5');
            Button.borderRadius(12);
            Button.onClick(() => {
                this.showNicknameDialog =
                    false;
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('保存');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                left: 5
            });
            Button.fontColor('#FFFFFF');
            Button.backgroundColor('#3C6FF0');
            Button.borderRadius(12);
            Button.enabled(this.nicknameInput
                .trim()
                .length > 0);
            Button.onClick(() => {
                void this.saveNickname();
            });
        }, Button);
        Button.pop();
        Row.pop();
        Column.pop();
        Column.pop();
    }
    // ============================================================
    // 创建频道弹窗
    // ============================================================
    createChannelDialog(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
            Column.backgroundColor('#00000066');
            Column.justifyContent(FlexAlign.Center);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('88%');
            Column.padding(20);
            Column.backgroundColor('#FFFFFF');
            Column.borderRadius(20);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('创建频道');
            Text.fontSize(21);
            Text.fontWeight(FontWeight.Bold);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            TextInput.create({
                placeholder: '频道名称',
                text: this.createChannelName
            });
            TextInput.width('100%');
            TextInput.height(48);
            TextInput.margin({
                top: 15
            });
            TextInput.onChange((value: string) => {
                this.createChannelName =
                    value
                        .substring(0, 24);
            });
        }, TextInput);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel(this.createChannelPrivate
                ? '🔒 私密频道'
                : '🌐 公开频道');
            Button.width('100%');
            Button.height(42);
            Button.margin({
                top: 9
            });
            Button.fontSize(13);
            Button.backgroundColor('#EEF1F5');
            Button.fontColor('#3B4148');
            Button.borderRadius(12);
            Button.onClick(() => {
                this.createChannelPrivate =
                    !this.createChannelPrivate;
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.createChannelPrivate) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        TextInput.create({
                            placeholder: '频道密码',
                            text: this.createChannelPassword
                        });
                        TextInput.width('100%');
                        TextInput.height(48);
                        TextInput.margin({
                            top: 9
                        });
                        TextInput.onChange((value: string) => {
                            this.createChannelPassword =
                                value
                                    .substring(0, 32);
                        });
                    }, TextInput);
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                });
            }
        }, If);
        If.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.margin({
                top: 16
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('取消');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                right: 5
            });
            Button.backgroundColor('#EEF1F5');
            Button.fontColor('#41474E');
            Button.borderRadius(12);
            Button.onClick(() => {
                this.resetCreateDialog();
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('创建');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                left: 5
            });
            Button.fontColor('#FFFFFF');
            Button.backgroundColor('#3C6FF0');
            Button.borderRadius(12);
            Button.enabled(this.createChannelName
                .trim()
                .length > 0 &&
                (!this.createChannelPrivate ||
                    this.createChannelPassword
                        .trim()
                        .length > 0));
            Button.onClick(() => {
                void this.createChannel();
            });
        }, Button);
        Button.pop();
        Row.pop();
        Column.pop();
        Column.pop();
    }
    // ============================================================
    // 私密频道密码
    // ============================================================
    passwordDialog(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
            Column.backgroundColor('#00000066');
            Column.justifyContent(FlexAlign.Center);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('88%');
            Column.padding(20);
            Column.backgroundColor('#FFFFFF');
            Column.borderRadius(20);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('🔒 输入频道密码');
            Text.fontSize(20);
            Text.fontWeight(FontWeight.Bold);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(`频道：${this.passwordChannel}`);
            Text.fontSize(13);
            Text.fontWeight(FontWeight.Bold);
            Text.margin({
                top: 10
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            TextInput.create({
                placeholder: '请输入密码',
                text: this.passwordInput
            });
            TextInput.width('100%');
            TextInput.height(48);
            TextInput.margin({
                top: 12
            });
            TextInput.onChange((value: string) => {
                this.passwordInput =
                    value;
            });
        }, TextInput);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.margin({
                top: 15
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('取消');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                right: 5
            });
            Button.fontColor('#41474E');
            Button.backgroundColor('#EEF1F5');
            Button.borderRadius(12);
            Button.onClick(() => {
                this.resetPasswordDialog();
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('加入');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                left: 5
            });
            Button.fontColor('#FFFFFF');
            Button.backgroundColor('#3C6FF0');
            Button.borderRadius(12);
            Button.enabled(this.passwordInput
                .trim()
                .length > 0);
            Button.onClick(() => {
                void this.joinPrivateChannel();
            });
        }, Button);
        Button.pop();
        Row.pop();
        Column.pop();
        Column.pop();
    }
    // ============================================================
    // 删除频道
    // ============================================================
    deleteChannelDialog(parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
            Column.height('100%');
            Column.backgroundColor('#00000066');
            Column.justifyContent(FlexAlign.Center);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('88%');
            Column.padding(20);
            Column.backgroundColor('#FFFFFF');
            Column.borderRadius(20);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('删除频道');
            Text.fontSize(21);
            Text.fontWeight(FontWeight.Bold);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(`确定删除「${this.currentChannel}」吗？`);
            Text.fontSize(14);
            Text.margin({
                top: 12
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('删除后无法恢复。');
            Text.fontSize(12);
            Text.fontColor('#858B93');
            Text.margin({
                top: 5
            });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.margin({
                top: 16
            });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('取消');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                right: 5
            });
            Button.fontColor('#41474E');
            Button.backgroundColor('#EEF1F5');
            Button.borderRadius(12);
            Button.onClick(() => {
                this.showDeleteDialog =
                    false;
            });
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('确定删除');
            Button.layoutWeight(1);
            Button.height(42);
            Button.margin({
                left: 5
            });
            Button.fontColor('#FFFFFF');
            Button.backgroundColor('#D32F2F');
            Button.borderRadius(12);
            Button.onClick(() => {
                void this.deleteCurrentChannel();
            });
        }, Button);
        Button.pop();
        Row.pop();
        Column.pop();
        Column.pop();
    }
    // ============================================================
    // 真实连接
    // ============================================================
    private async connectServer(): Promise<void> {
        if (this.connected) {
            return;
        }
        const name: string = this.nickname.trim();
        /*
         * 没有昵称时也允许连接。
         *
         * VPS 会按 deviceId 创建默认用户名。
         */
        await this.walkieClient.connect(name);
    }
    // ============================================================
    // 真实断开
    // ============================================================
    private async disconnectServer(): Promise<void> {
        await this.walkieClient.disconnect();
    }
    // ============================================================
    // 保存昵称
    // ============================================================
    private async saveNickname(): Promise<void> {
        const value: string = this.nicknameInput
            .trim()
            .substring(0, 20);
        if (value.length === 0) {
            return;
        }
        this.nickname =
            value;
        this.showNicknameDialog =
            false;
        await this.walkieClient
            .setNickname(value);
    }
    // ============================================================
    // 创建频道
    // ============================================================
    private async createChannel(): Promise<void> {
        const name: string = this.createChannelName
            .trim();
        if (name.length === 0) {
            return;
        }
        if (this.createChannelPrivate &&
            this.createChannelPassword
                .trim()
                .length === 0) {
            return;
        }
        await this.walkieClient
            .createChannel(name, this.createChannelPrivate, this.createChannelPassword
            .trim());
        this.resetCreateDialog();
    }
    // ============================================================
    // 重置创建频道
    // ============================================================
    private resetCreateDialog(): void {
        this.showCreateChannelDialog =
            false;
        this.createChannelName =
            '';
        this.createChannelPrivate =
            false;
        this.createChannelPassword =
            '';
    }
    // ============================================================
    // 选择频道
    // ============================================================
    private selectChannel(channel: WalkieChannel): void {
        if (!this.connected) {
            return;
        }
        if (channel.name ===
            this.currentChannel) {
            return;
        }
        if (channel.isPrivate ||
            channel.requirePassword) {
            this.passwordChannel =
                channel.name;
            this.passwordInput =
                '';
            this.showPasswordDialog =
                true;
            return;
        }
        void this.walkieClient
            .joinChannel(channel.name);
    }
    // ============================================================
    // 加入私密频道
    // ============================================================
    private async joinPrivateChannel(): Promise<void> {
        const password: string = this.passwordInput
            .trim();
        if (password.length === 0) {
            return;
        }
        await this.walkieClient
            .joinChannel(this.passwordChannel, password);
        this.resetPasswordDialog();
    }
    // ============================================================
    // 重置密码弹窗
    // ============================================================
    private resetPasswordDialog(): void {
        this.showPasswordDialog =
            false;
        this.passwordChannel =
            '';
        this.passwordInput =
            '';
    }
    // ============================================================
    // 删除当前频道
    // ============================================================
    private async deleteCurrentChannel(): Promise<void> {
        if (this.currentChannel ===
            'public') {
            this.showDeleteDialog =
                false;
            return;
        }
        await this.walkieClient
            .deleteChannel(this.currentChannel);
        this.showDeleteDialog =
            false;
    }
    // ============================================================
    // 网络摘要
    // ============================================================
    private buildNetworkSummary(): string {
        const latencyText: string = this.networkLatency >= 0
            ? `${this.networkLatency}ms`
            : '--';
        const lossText: string = `${this.networkLoss.toFixed(1)}%`;
        const jitterText: string = this.networkJitter >= 0
            ? `${this.networkJitter}ms`
            : '--';
        return (`${this.networkType} · ` +
            `${this.networkQuality} · ` +
            `延迟 ${latencyText} · ` +
            `丢包 ${lossText} · ` +
            `抖动 ${jitterText}`);
    }
    // ============================================================
    // 应用 WalkieClient 状态
    // ============================================================
    private applyClientState(state: WalkieClientState): void {
        /*
         * UI 状态全部以 WalkieClient 为准。
         */
        this.connected =
            state.connected;
        this.nickname =
            state.nickname;
        this.myUserId =
            state.userId;
        this.currentChannel =
            state.currentChannel;
        this.onlineUsers =
            state.onlineUsers;
        this.channels =
            state.channels;
        this.currentOnlineCount =
            state.onlineUsers.length;
        this.currentPrivate =
            state.currentChannelPrivate;
        this.networkLatency =
            state.network.latency;
        this.networkLoss =
            state.network.loss;
        this.networkQuality =
            state.network.quality;
        this.networkJitter =
            state.network.jitter;
        this.talkStatus =
            state.talkStatus;
        if (state.message.length > 0) {
            this.channelMessage =
                state.message;
        }
        /*
         * 网络类型目前 UDP 层没有直接返回。
         * 先根据连接状态显示。
         *
         * 后面接 HarmonyOS 网络能力 API。
         */
        if (this.connected) {
            this.networkType =
                '公网 UDP';
        }
        else {
            this.networkType =
                '离线';
        }
        /*
         * 频道人数从服务器频道数据同步。
         */
        for (let index = 0; index < this.channels.length; index++) {
            const channel: WalkieChannel = this.channels[index];
            if (channel.name ===
                this.currentChannel) {
                this.currentOnlineCount =
                    channel.onlineCount;
                break;
            }
        }
    }
    rerender() {
        this.updateDirtyElements();
    }
    static getEntryName(): string {
        return "Index";
    }
}
registerNamedRoute(() => new Index(undefined, {}), "", { bundleName: "com.walkie.brothers", moduleName: "entry", pagePath: "pages/Index", pageFullPath: "entry/src/main/ets/pages/Index", integratedHsp: "false", moduleType: "followWithHap" });
