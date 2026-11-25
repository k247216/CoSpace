<template>
    <section>
        <div class="toolbar">
            <div>
                <h3>收集任务</h3>
                <p class="co-page-description">发起一次资料交付，按人员跟踪提交情况。</p>
            </div>
            <div>
                <el-button @click="reload">刷新任务</el-button
                ><el-button type="primary" @click="openCreate">新建收集</el-button>
            </div>
        </div>
        <el-table :data="collections" empty-text="还没有收集任务">
            <el-table-column label="收集名称" min-width="170"
                ><template #default="{ row }"
                    ><div class="co-file-name">
                        <span class="co-file-icon"><co-icon name="inbox" /></span
                        ><strong>{{ row.title }}</strong>
                    </div></template
                ></el-table-column
            >
            <el-table-column label="提交进度" min-width="150"
                ><template #default="{ row }"
                    ><div class="collection-progress">
                        <span>{{ row.submittedPeople }} / {{ row.invitedCount }} 人已提交</span
                        ><el-progress
                            :percentage="
                                row.invitedCount
                                    ? Math.round((row.submittedPeople / row.invitedCount) * 100)
                                    : 0
                            "
                            :show-text="false"
                            :stroke-width="4"
                        /></div></template
            ></el-table-column>
            <el-table-column label="文件" width="80"
                ><template #default="{ row }">{{ row.submittedFiles }} 份</template></el-table-column
            >
            <el-table-column label="截止时间"
                ><template #default="{ row }">{{ formatDate(row.deadline) }}</template></el-table-column
            >
            <el-table-column label="状态"
                ><template #default="{ row }">{{
                    ended(row) ? '已结束' : '进行中'
                }}</template></el-table-column
            >
            <el-table-column label="操作"
                ><template #default="{ row }"
                    ><el-button link @click="manage(row)">管理收集</el-button></template
                ></el-table-column
            >
        </el-table>
        <el-dialog
            v-model="creating"
            title="新建文件收集"
            width="620px"
            :close-on-click-modal="false"
            :before-close="closeCreate"
        >
            <el-form label-position="top" @submit.prevent="create">
                <el-form-item label="收集名称" required
                    ><el-input v-model="form.title" maxlength="100" placeholder="例如：课程报告提交"
                /></el-form-item>
                <el-form-item label="归档位置"
                    ><span
                        >当前团队的{{
                            parentId === workspaceId ? '根目录' : '当前文件夹'
                        }}，创建后固定</span
                    ></el-form-item
                >
                <el-form-item label="截止时间" required
                    ><el-date-picker v-model="form.deadline" type="datetime" placeholder="选择截止时间"
                /></el-form-item>
                <el-form-item label="提交人" required
                    ><recipient-picker
                        :key="pickerKey"
                        v-model="form.recipientIds"
                        :workspace-id="workspaceId"
                /></el-form-item>
                <p class="hint">
                    可多选团队成员。邀请外部用户时，输入其完整用户名或
                    ID，选中搜索结果。只读或已移除成员不能提交。
                </p>
                <el-form-item label="单文件上限（MiB）"
                    ><el-input-number v-model="form.maxMiB" :min="1" :max="3072" :precision="0"
                /></el-form-item>
                <el-form-item label="文件类型"
                    ><el-input
                        v-model="form.allowedSuffix"
                        placeholder="留空不限，或填写 .pdf 等一个扩展名"
                /></el-form-item>
                <p>创建后，提交人可在侧栏“我的收集”看到任务。文件直接归档到团队，占用团队容量。</p>
                <p v-if="formError" role="alert">{{ formError }}</p>
            </el-form>
            <template #footer
                ><el-button :disabled="saving" @click="creating = false">取消</el-button
                ><el-button type="primary" :loading="saving" @click="create"
                    >创建并邀请</el-button
                ></template
            >
        </el-dialog>
        <el-dialog v-model="managing" :title="selected?.title || '管理收集'" width="1000px">
            <template v-if="selected">
                <p class="collection-detail-meta">
                    截止 {{ formatDate(selected.deadline) }} ·
                    {{ ended(selected) ? '已结束' : '进行中' }}
                </p>
                <p class="collection-detail-link">
                    <router-link :to="'/collections/' + selected.id">打开收集详情和提交清单</router-link>
                </p>
                <collection-report
                    ref="report"
                    :collection="selected"
                    manager
                    allow-revoke
                    @revoke="revoke"
                />
                <template v-if="!ended(selected)">
                    <h4>添加提交人</h4>
                    <recipient-picker
                        :key="selected.id"
                        v-model="additional"
                        :workspace-id="workspaceId"
                    />
                    <div class="toolbar">
                        <el-button :disabled="!additional.length || saving" @click="add"
                            >邀请所选用户</el-button
                        ><el-button type="danger" :disabled="saving" @click="closeCollection"
                            >关闭收集</el-button
                        >
                    </div>
                </template>
            </template>
        </el-dialog>
    </section>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElDatePicker, ElInputNumber, ElMessage, ElMessageBox } from 'element-plus'
import http from '../../../utils/http'
import RecipientPicker from './recipient-picker.vue'
import CollectionReport from './collection-report.vue'
import CoIcon from '../../../components/cospace/co-icon.vue'
const props = defineProps({
    workspaceId: { type: String, required: true },
    parentId: { type: String, required: true },
})
const report = ref(null)
const collections = ref([]),
    creating = ref(false),
    managing = ref(false),
    saving = ref(false)
const selected = ref(null),
    additional = ref([]),
    pickerKey = ref(0),
    formError = ref('')
const form = reactive({ title: '', deadline: null, recipientIds: [], maxMiB: 100, allowedSuffix: '' })
const root = () => '/workspaces/' + props.workspaceId + '/collections'
const ended = (item) => item.closed || new Date(item.deadline).getTime() <= Date.now()
const formatDate = (value) => new Date(value).toLocaleString()
async function reload() {
    try {
        await refresh()
    } catch (failure) {
        ElMessage.error(failure.message || '无法刷新任务')
    }
}
async function refresh() {
    collections.value = (await http({ url: root() })).data
}
function openCreate() {
    Object.assign(form, { title: '', deadline: null, recipientIds: [], maxMiB: 100, allowedSuffix: '' })
    formError.value = ''
    pickerKey.value++
    creating.value = true
}
function closeCreate(done) {
    if (!saving.value) done()
}
async function create() {
    if (saving.value) return
    formError.value = ''
    if (!form.title.trim()) {
        formError.value = '请填写收集名称'
        return
    }
    if (
        !(form.deadline instanceof Date) ||
        !Number.isFinite(form.deadline.getTime()) ||
        form.deadline.getTime() <= Date.now()
    ) {
        formError.value = '请选择将来的截止时间'
        return
    }
    if (!form.recipientIds.length) {
        formError.value = '请至少选择一位提交人'
        return
    }
    const suffix = form.allowedSuffix.trim()
    if (!/^(\.[a-zA-Z0-9]{1,16})?$/.test(suffix)) {
        formError.value = '扩展名请填写 .pdf 等格式，或留空'
        return
    }
    saving.value = true
    try {
        await http({
            url: root(),
            method: 'post',
            data: {
                parentId: props.parentId,
                title: form.title.trim(),
                deadline: form.deadline.toISOString(),
                maxFileBytes: form.maxMiB * 1048576,
                allowedSuffix: suffix,
                recipientIds: form.recipientIds,
            },
        })
        creating.value = false
        await refresh()
        ElMessage.success('收集已创建，所选用户可在“我的收集”查看')
    } catch (failure) {
        formError.value = failure.message || '创建失败，请重试'
    } finally {
        saving.value = false
    }
}
async function manage(row) {
    selected.value = row
    additional.value = []
    try {
        managing.value = true
    } catch (failure) {
        ElMessage.error(failure.message)
    }
}
async function change(operation) {
    if (saving.value) return
    saving.value = true
    try {
        await operation()
        await refresh()
        selected.value = collections.value.find((item) => item.id === selected.value.id)
        additional.value = []
        await report.value?.load()
    } catch (failure) {
        ElMessage.error(failure.message || '操作失败')
    } finally {
        saving.value = false
    }
}
async function add() {
    await change(() =>
        http({
            url: root() + '/' + selected.value.id + '/invitees',
            method: 'post',
            data: { recipientIds: additional.value },
        }),
    )
}
async function revoke(user) {
    await change(() =>
        http({
            url: root() + '/' + selected.value.id + '/invitees/' + user.id,
            method: 'put',
            data: { active: false },
        }),
    )
}
async function closeCollection() {
    try {
        await ElMessageBox.confirm('关闭后所有受邀者都不能继续提交，确认关闭？', '关闭收集', {
            confirmButtonText: '确认关闭',
            cancelButtonText: '取消',
            type: 'warning',
        })
    } catch {
        return
    }
    await change(() => http({ url: root() + '/' + selected.value.id + '/close', method: 'post' }))
}
onMounted(() => refresh().catch((failure) => ElMessage.error(failure.message)))
</script>
<style scoped>
.collection-detail-meta {
    color: #8b957d;
    font-size: 12px;
    margin-bottom: 8px;
}
.collection-detail-link {
    font-size: 12px;
    margin-bottom: 20px;
}
.toolbar {
    display: flex;
    gap: 12px;
    align-items: center;
    margin: 16px 0;
}
.collection-progress {
    max-width: 160px;
    font-size: 11px;
}
.collection-progress :deep(.el-progress) {
    margin-top: 9px;
}
.hint {
    color: #606266;
    font-size: 13px;
}
[role='alert'] {
    color: #b42318;
}
</style>
