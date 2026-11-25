<template>
    <section class="team-page">
        <div class="co-page-heading">
            <div>
                <h2>团队空间</h2>
            </div>
            <div class="team-create-inline">
                <el-input v-model="spaceName" placeholder="新空间名称" maxlength="100" /><el-button
                    type="primary"
                    @click="createSpace"
                    >创建空间</el-button
                >
            </div>
        </div>
        <p v-if="error" class="co-error" role="alert">{{ error }}</p>
        <div class="space-switcher">
            <el-select v-model="workspaceId" placeholder="选择空间" @change="openSpace"
                ><el-option
                    v-for="space in spaces"
                    :key="space.id"
                    :label="space.name"
                    :value="space.id" /></el-select
            ><span class="identity-note"
                >我的用户 ID
                <button class="co-text-button" title="复制用户 ID" @click="copyIdentity">
                    {{ identity }}
                </button></span
            >
        </div>
        <template v-if="current">
            <div class="workspace-banner">
                <div class="workspace-banner-icon"><co-icon name="users" /></div>
                <div>
                    <h3>{{ current.name }}</h3>
                    <p>
                        {{ roleNames[current.role] }}
                        <span>· {{ formatBytes(current.totalBytes) }} 团队容量</span>
                    </p>
                </div>
                <div class="workspace-capacity">
                    <capacity-bar
                        :used="current.usedBytes"
                        :reserved="current.reservedBytes"
                        :total="current.totalBytes"
                        label="团队容量占用"
                    />
                </div>
            </div>
            <div class="co-tabs" role="tablist" aria-label="团队功能">
                <button
                    role="tab"
                    :aria-selected="tab === 'files'"
                    :class="{ 'is-active': tab === 'files' }"
                    @click="tab = 'files'"
                >
                    团队文件</button
                ><template v-if="manager"
                    ><button
                        v-for="item in managementTabs"
                        :key="item.value"
                        role="tab"
                        :aria-selected="tab === item.value"
                        :class="{ 'is-active': tab === item.value }"
                        @click="tab = item.value"
                    >
                        {{ item.label }}
                    </button></template
                >
            </div>
            <section v-if="tab === 'files'" class="co-card">
                <div class="co-section-title">
                    <div>
                        <h3>团队资料</h3>
                        <p>统一整理团队文件，收集提交会保留明确来源。</p>
                    </div>
                    <el-button @click="refresh">刷新</el-button>
                </div>
                <div class="co-toolbar">
                    <upload-button
                        v-if="current.role !== 'VIEWER' && !recycle"
                        :round-flag="true"
                        size="default"
                        :parent-id="parentId"
                        :workspace-id="workspaceId"
                        @refresh="refresh"
                    /><el-button v-if="current.role !== 'VIEWER' && !recycle" @click="createFolder"
                        >新建文件夹</el-button
                    ><el-button @click="toggleRecycle">{{
                        recycle ? '返回文件' : '团队回收站'
                    }}</el-button
                    ><el-input
                        v-model="keyword"
                        placeholder="搜索团队文件"
                        clearable
                        @keyup.enter="loadFiles"
                        @clear="loadFiles"
                    />
                </div>
                <div class="file-list-context">
                    <div>
                        <el-button link @click="goRoot">根目录</el-button
                        ><span v-if="path.length"> / 当前文件夹</span
                        ><el-button v-if="path.length" link @click="goBack">上一级</el-button>
                    </div>
                    <div class="co-filter-pills">
                        <button
                            v-for="item in sourceFilters"
                            :key="item.value"
                            :class="{ 'is-active': source === item.value }"
                            @click="source = item.value"
                        >
                            {{ item.label }}
                        </button>
                    </div>
                </div>
                <el-table :data="visibleNodes" empty-text="当前目录暂无文件">
                    <el-table-column label="名称" min-width="230"
                        ><template #default="{ row }"
                            ><div class="co-file-name">
                                <span class="co-file-icon"
                                    ><co-icon :name="row.folder ? 'folder' : 'file'"
                                /></span>
                                <div>
                                    <strong
                                        ><button
                                            v-if="row.folder && !recycle"
                                            class="co-text-button file-name-button"
                                            @click="enter(row)"
                                        >
                                            {{ row.name }}</button
                                        ><span v-else>{{ row.name }}</span></strong
                                    ><small>{{ formatDate(row.createdAt) }}</small>
                                </div>
                            </div></template
                        ></el-table-column
                    >
                    <el-table-column label="来源" min-width="160"
                        ><template #default="{ row }"
                            ><span v-if="row.folder" class="co-source">文件夹</span
                            ><span
                                v-else
                                class="co-source"
                                :class="{ 'is-collection': row.collectionId }"
                                ><co-icon :name="row.collectionId ? 'inbox' : 'cloud'" />{{
                                    row.collectionId ? row.collectionTitle || '收集文件' : '普通上传'
                                }}</span
                            ></template
                        ></el-table-column
                    >
                    <el-table-column label="上传人" min-width="100"
                        ><template #default="{ row }">{{
                            row.creatorName || row.creator
                        }}</template></el-table-column
                    >
                    <el-table-column label="大小" width="100"
                        ><template #default="{ row }">{{
                            row.folder ? '—' : formatBytes(row.bytes)
                        }}</template></el-table-column
                    >
                    <el-table-column label="操作" min-width="240"
                        ><template #default="{ row }"
                            ><el-button v-if="row.folder && !recycle" link @click="enter(row)"
                                >打开</el-button
                            ><el-button
                                v-if="!row.folder && !recycle && row.bytes <= 20971520"
                                link
                                @click="preview(row)"
                                >预览</el-button
                            ><el-button v-if="!row.folder && !recycle" link @click="download(row)"
                                >下载</el-button
                            ><el-button
                                v-if="manager && !row.folder && !recycle"
                                link
                                @click="share(row)"
                                >指定接收人分享</el-button
                            ><el-button v-if="current.role !== 'VIEWER'" link @click="remove(row)">{{
                                recycle ? '彻底删除' : '删除'
                            }}</el-button
                            ><el-button
                                v-if="recycle && current.role !== 'VIEWER'"
                                link
                                @click="restore(row)"
                                >恢复</el-button
                            ></template
                        ></el-table-column
                    >
                </el-table>
            </section>
            <section v-if="tab === 'collections' && manager" class="co-card">
                <collection-manager
                    :key="workspaceId"
                    :workspace-id="workspaceId"
                    :parent-id="parentId"
                />
            </section>
            <section v-if="tab === 'members' && manager" class="co-card">
                <div class="co-section-title">
                    <div>
                        <h3>成员</h3>
                        <p>添加已注册用户，按职责分配团队访问权限。</p>
                    </div>
                    <span class="co-status"
                        >{{ members.filter((member) => member.active).length }} 位成员</span
                    >
                </div>
                <p class="co-page-description">
                    对方可在团队空间页面复制用户 ID。请使用独立登录窗口测试不同账号。
                </p>
                <div class="team-toolbar">
                    <el-input v-model="memberId" placeholder="已注册用户 ID" /><el-select
                        v-model="memberRole"
                        ><el-option
                            v-for="role in assignableRoles"
                            :key="role"
                            :value="role"
                            :label="roleNames[role]" /></el-select
                    ><el-button
                        type="primary"
                        :disabled="!memberId.trim()"
                        :loading="memberSubmitting"
                        @click="assign"
                        >添加或更新成员</el-button
                    >
                </div>
                <el-table :data="members"
                    ><el-table-column prop="userId" label="用户 ID" min-width="200" /><el-table-column
                        label="角色"
                        min-width="100"
                        ><template #default="{ row }">{{
                            roleNames[row.role]
                        }}</template></el-table-column
                    ><el-table-column label="状态" width="110"
                        ><template #default="{ row }"
                            ><span class="co-status" :class="{ muted: !row.active }">{{
                                row.active ? '有效' : '已移除'
                            }}</span></template
                        ></el-table-column
                    ><el-table-column label="操作" width="85"
                        ><template #default="{ row }"
                            ><el-button
                                v-if="row.role !== 'OWNER' && row.active"
                                link
                                @click="removeMember(row)"
                                >移除</el-button
                            ></template
                        ></el-table-column
                    ></el-table
                >
            </section>
            <section v-if="tab === 'shares' && manager" class="co-card">
                <div class="co-section-title">
                    <div>
                        <h3>受控分享</h3>
                        <p>从文件列表指定接收人分享，链接仅对授权账号有效。</p>
                    </div>
                </div>
                <el-table :data="shares" empty-text="还没有团队分享"
                    ><el-table-column
                        prop="recipientId"
                        label="接收人 ID"
                        min-width="180"
                    /><el-table-column label="到期时间" min-width="150"
                        ><template #default="{ row }">{{
                            formatDate(row.expiresAt)
                        }}</template></el-table-column
                    ><el-table-column label="状态" width="100"
                        ><template #default="{ row }"
                            ><span class="co-status" :class="{ muted: row.revoked }">{{
                                row.revoked ? '已撤销' : '有效'
                            }}</span></template
                        ></el-table-column
                    ><el-table-column label="操作" width="90"
                        ><template #default="{ row }"
                            ><el-button v-if="!row.revoked" link @click="revokeShare(row)"
                                >撤销</el-button
                            ></template
                        ></el-table-column
                    ></el-table
                >
            </section>
        </template>
        <div v-else-if="spaces.length" class="space-card-grid">
            <button
                v-for="space in spaces"
                :key="space.id"
                class="space-card"
                @click="selectSpace(space.id)"
            >
                <span class="workspace-banner-icon"><co-icon name="users" /></span>
                <h3>{{ space.name }}</h3>
                <p>{{ roleNames[space.role] }} · 已用 {{ formatBytes(space.usedBytes) }}</p>
                <span class="space-card-link">进入空间 <co-icon name="arrow" /></span>
            </button>
        </div>
        <div v-else class="co-card co-empty">
            <div class="co-empty-icon"><co-icon name="users" /></div>
            <h3>为下一次合作，创建一个空间</h3>
            <p>创建团队后即可邀请成员，整理共享资料并发起文件收集。</p>
        </div>
    </section>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import CoIcon from '../../components/cospace/co-icon.vue'
import CapacityBar from '../../components/cospace/capacity-bar.vue'
import { formatBytes, formatDate } from '../../utils/format'
import { copyText } from '../../utils/clipboard'
import { ElMessageBox, ElMessage } from 'element-plus'
import http from '../../utils/http'
import { getToken } from '../../utils/cookie'
import panUtil from '../../utils/common'
import UploadButton from '../../components/buttons/upload-button/index.vue'
import CollectionManager from './components/collection-manager.vue'

const keyword = ref('')
const identity = ref(''),
    shares = ref([])
const spaces = ref([]),
    workspaceId = ref(''),
    parentId = ref(''),
    spaceName = ref(''),
    nodes = ref([])
const error = ref(''),
    recycle = ref(false),
    path = ref([]),
    members = ref([])
const memberSubmitting = ref(false)
const memberId = ref(''),
    memberRole = ref('MEMBER')
const roleNames = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员', VIEWER: '只读成员' }
const current = computed(() => spaces.value.find((space) => space.id === workspaceId.value))
const manager = computed(() => ['OWNER', 'ADMIN'].includes(current.value?.role))
const assignableRoles = computed(() =>
    current.value?.role === 'OWNER' ? ['ADMIN', 'MEMBER', 'VIEWER'] : ['MEMBER', 'VIEWER'],
)
const root = () => '/workspaces/' + workspaceId.value
const tab = ref('files'),
    source = ref('all')
const managementTabs = [
    { value: 'collections', label: '收集任务' },
    { value: 'members', label: '成员管理' },
    { value: 'shares', label: '分享管理' },
]
const sourceFilters = [
    { value: 'all', label: '全部文件' },
    { value: 'normal', label: '普通上传' },
    { value: 'collection', label: '收集文件' },
]
const visibleNodes = computed(() =>
    nodes.value.filter(
        (node) =>
            node.folder ||
            source.value === 'all' ||
            (source.value === 'collection' ? node.collectionId : !node.collectionId),
    ),
)
async function toggleRecycle() {
    recycle.value = !recycle.value
    keyword.value = ''
    source.value = 'all'
    await loadFiles()
}
async function selectSpace(id) {
    workspaceId.value = id
    await openSpace()
}
async function copyIdentity() {
    try {
        await copyText(identity.value)
        ElMessage.success('用户 ID 已复制')
    } catch {
        ElMessage.error('复制失败，请手动选择用户 ID')
    }
}

async function act(operation) {
    error.value = ''
    try {
        return await operation()
    } catch (failure) {
        error.value = failure.message || '操作未完成'
    }
}
async function loadFiles() {
    await act(async () => {
        nodes.value = (
            await http({
                url: root() + (keyword.value && !recycle.value ? '/files/search' : '/files'),
                params: { parentId: parentId.value, recycle: recycle.value, keyword: keyword.value },
            })
        ).data
    })
}
async function refresh() {
    await act(async () => {
        spaces.value = (await http({ url: '/workspaces' })).data
        if (current.value) {
            if (!manager.value) tab.value = 'files'
            await loadFiles()
            if (manager.value) {
                members.value = (await http({ url: root() + '/members' })).data
                shares.value = (await http({ url: root() + '/shares' })).data
            }
        }
    })
}
async function openSpace() {
    tab.value = 'files'
    source.value = 'all'
    parentId.value = workspaceId.value
    path.value = []
    keyword.value = ''
    recycle.value = false
    await refresh()
}
async function createSpace() {
    await act(async () => {
        const result = await http({
            url: '/workspaces',
            method: 'post',
            data: { name: spaceName.value },
        })
        await refresh()
        workspaceId.value = result.data.id
        await openSpace()
    })
}
async function goRoot() {
    parentId.value = workspaceId.value
    path.value = []
    await loadFiles()
}
async function goBack() {
    parentId.value = path.value.pop() || workspaceId.value
    await loadFiles()
}
async function enter(row) {
    path.value.push(parentId.value)
    parentId.value = row.id
    await loadFiles()
}
async function createFolder() {
    await act(async () => {
        const { value } = await ElMessageBox.prompt('文件夹名称', '新建文件夹')
        await http({
            url: root() + '/files/folders',
            method: 'post',
            data: { parentId: parentId.value, name: value },
        })
        await refresh()
    })
}
async function remove(row) {
    await act(async () => {
        await ElMessageBox.confirm(recycle.value ? '确认彻底删除该文件？' : '确认移入回收站？', row.name)
        await http({
            url: root() + '/files/' + row.id,
            method: 'delete',
            params: { purge: recycle.value },
        })
        await refresh()
    })
}
async function restore(row) {
    await act(async () => {
        await http({ url: root() + '/files/' + row.id + '/restore', method: 'post' })
        await refresh()
    })
}
async function preview(row) {
    const tab = window.open('', '_blank')
    await act(async () => {
        try {
            const response = await fetch(
                panUtil.getUrlPrefix() + root() + '/files/' + row.id + '/preview',
                { headers: { Authorization: getToken() } },
            )
            if (!response.ok || !response.headers.get('x-content-type-options'))
                throw new Error('预览暂不可用')
            const url = URL.createObjectURL(await response.blob())
            if (tab) tab.location.replace(url)
            setTimeout(() => URL.revokeObjectURL(url), 60000)
        } catch (failure) {
            if (tab) tab.close()
            throw failure
        }
    })
}

async function download(row) {
    await act(async () => {
        const response = await fetch(
            panUtil.getUrlPrefix() + root() + '/files/' + row.id + '/download',
            { headers: { Authorization: getToken() } },
        )
        if (!response.ok || !response.headers.get('content-disposition'))
            throw new Error('下载失败，请刷新后重试')
        const url = URL.createObjectURL(await response.blob())
        const link = document.createElement('a')
        link.href = url
        link.download = row.name
        link.click()
        setTimeout(() => URL.revokeObjectURL(url), 60000)
    })
}
function registeredUserId(value) {
    const id = value.trim()
    if (!/^[1-9][0-9]{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) {
        throw new Error('请填写对方的完整用户 ID，可从对方的团队空间页面复制')
    }
    return id
}

async function assign() {
    await act(async () => {
        const userId = registeredUserId(memberId.value)
        memberSubmitting.value = true
        try {
            await http({
                url: root() + '/members/' + userId,
                method: 'put',
                data: { role: memberRole.value },
            })
            memberId.value = ''
            await refresh()
            ElMessage.success('成员已更新')
        } finally {
            memberSubmitting.value = false
        }
    })
}
async function removeMember(row) {
    await act(async () => {
        await http({ url: root() + '/members/' + row.userId, method: 'delete' })
        await refresh()
    })
}
async function share(row) {
    await act(async () => {
        const { value } = await ElMessageBox.prompt(
            '已注册接收人的用户 ID，分享 24 小时后到期',
            '指定接收人',
        )
        const result = await http({
            url: root() + '/shares',
            method: 'post',
            data: {
                fileId: row.id,
                recipientId: value,
                expiresAt: new Date(Date.now() + 86400000).toISOString(),
            },
        })
        await ElMessageBox.alert(location.origin + '/team-shares/' + result.data, '接收人登录后可下载')
        await refresh()
    })
}
async function revokeShare(row) {
    await act(async () => {
        await http({ url: root() + '/shares/' + row.id, method: 'delete' })
        await refresh()
    })
}
onMounted(async () => {
    await act(async () => {
        identity.value = (await http({ url: '/workspaces/me' })).data
    })
    await refresh()
})
</script>

<style scoped>
.team-create-inline {
    display: flex;
    gap: 9px;
    align-items: center;
}
.team-create-inline .el-input {
    width: 170px;
}
.space-switcher {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 22px;
    gap: 18px;
}
.space-switcher > .el-select {
    width: 240px;
}
.identity-note {
    font-size: 10px;
    color: #929b85;
}
.identity-note button {
    font-size: 10px;
    margin-left: 5px;
}
.workspace-banner {
    display: flex;
    align-items: center;
    gap: 18px;
    background: #eaeee1;
    border: 1px solid #e0e6d5;
    border-radius: 12px;
    padding: 24px 26px;
    margin-bottom: 22px;
}
.workspace-banner h3 {
    font-size: 19px;
    margin: 0 0 7px;
}
.workspace-banner p {
    font-size: 11px;
    color: #6f805f;
    margin: 0;
}
.workspace-banner p span {
    color: #94a085;
}
.workspace-banner-icon {
    display: grid;
    place-items: center;
    flex-shrink: 0;
    width: 48px;
    height: 48px;
    border: 1px solid #d3dec5;
    border-radius: 12px;
    background: #e2e9d5;
    color: #70865e;
}
.workspace-banner-icon svg {
    width: 24px;
    height: 24px;
}
.workspace-capacity {
    width: 200px;
    margin-left: auto;
    font-size: 11px;
    color: #6f805f;
}
.workspace-capacity small {
    font-size: 10px;
    color: #9aa58c;
}
.workspace-capacity :deep(.el-progress) {
    margin: 10px 0 6px;
}
.file-list-context {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 20px;
    font-size: 11px;
    color: #929b85;
    flex-wrap: wrap;
}
.file-name-button {
    font-size: 13px;
    font-weight: 550;
}
.team-toolbar {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin: 22px 0;
}
.team-toolbar .el-input {
    width: 240px;
}
.team-toolbar .el-select {
    width: 150px;
}
.space-card-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
    gap: 18px;
}
.space-card {
    border: 1px solid var(--co-line);
    border-radius: 12px;
    padding: 24px;
    background: var(--co-surface);
    text-align: left;
    color: var(--co-ink);
}
.space-card:hover {
    border-color: #aab99a;
}
.space-card h3 {
    margin: 19px 0 10px;
    font-size: 16px;
}
.space-card p {
    font-size: 11px;
    color: var(--co-muted);
}
.space-card-link {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 11px;
    margin-top: 28px;
    color: var(--co-accent);
}
.space-card-link svg {
    width: 15px;
}
@media (max-width: 760px) {
    .team-create-inline {
        width: 100%;
    }
    .team-create-inline .el-input {
        flex: 1;
    }
    .space-switcher {
        flex-wrap: wrap;
    }
    .identity-note {
        width: 100%;
        font-size: 10px;
    }
    .workspace-banner {
        padding: 20px 18px;
        gap: 12px;
        flex-wrap: wrap;
    }
    .workspace-capacity {
        width: 100%;
        margin-top: 10px;
    }
    .workspace-banner h3 {
        font-size: 17px;
    }
    .space-switcher > .el-select {
        width: 100%;
    }
    .team-toolbar .el-input {
        width: 100%;
    }
    .team-toolbar .el-select {
        flex: 1;
    }
    .co-toolbar .el-input {
        width: 100%;
    }
}
</style>
