<template>
    <div class="collection-report">
        <div v-if="manager" class="co-metrics">
            <div class="co-metric">
                <span>已提交</span
                ><strong
                    >{{ activePeople.filter((item) => item.submittedCount > 0).length
                    }}<em> / {{ activePeople.length }}</em></strong
                ><small>当前受邀人员</small>
            </div>
            <div class="co-metric">
                <span>尚未提交</span
                ><strong>{{ activePeople.filter((item) => !item.submittedCount).length }}</strong
                ><small>当前受邀人员</small>
            </div>
            <div class="co-metric">
                <span>已收到文件</span><strong>{{ submissions.length }}</strong
                ><small>包含已撤销人员的历史提交</small>
            </div>
        </div>
        <p v-if="error" class="co-error" role="alert">{{ error }}</p>
        <template v-if="manager">
            <div class="co-section-title">
                <div>
                    <h3>人员提交情况</h3>
                    <p>按人核对进度，撤销邀请会保留历史提交。</p>
                </div>
                <el-button :loading="loading" @click="load">刷新进度</el-button>
            </div>
            <div class="co-filter-pills">
                <button
                    v-for="item in filters"
                    :key="item.value"
                    :class="{ 'is-active': filter === item.value }"
                    @click="filter = item.value"
                >
                    {{ item.label }}
                </button>
            </div>
            <el-table :data="filteredPeople" empty-text="没有符合条件的提交人" class="participant-table">
                <el-table-column label="提交人" min-width="135"
                    ><template #default="{ row }"
                        ><div class="report-person">
                            <span class="co-avatar">{{ row.username?.slice(0, 1).toUpperCase() }}</span>
                            <div>
                                <strong>{{ row.username }}</strong><small
                                    >{{ row.submittedCount }} 份文件</small
                                >
                            </div>
                        </div></template
                    ></el-table-column
                >
                <el-table-column label="状态" width="100"
                    ><template #default="{ row }"
                        ><span
                            class="co-status"
                            :class="{ muted: !row.active, clay: row.active && !row.submittedCount }"
                            >{{
                                !row.active ? '邀请已撤销' : row.submittedCount ? '已提交' : '未提交'
                            }}</span
                        ></template
                    ></el-table-column
                >
                <el-table-column label="提交文件" min-width="170"
                    ><template #default="{ row }"
                        ><div v-if="row.submittedCount" class="report-file-list">
                            <span v-for="file in filesFor(row.id)" :key="file.uploadId">{{
                                file.filename || '历史文件（名称未保留）'
                            }}</span>
                        </div>
                        <span v-else class="report-muted">等待提交</span></template
                    ></el-table-column
                >
                <el-table-column label="最近提交" width="125"
                    ><template #default="{ row }">{{
                        formatDate(row.lastSubmittedAt)
                    }}</template></el-table-column
                >
                <el-table-column v-if="allowRevoke" label="操作" width="85"
                    ><template #default="{ row }"
                        ><el-button v-if="row.active" link @click="$emit('revoke', row)"
                            >撤销邀请</el-button
                        ></template
                    ></el-table-column
                >
            </el-table>
        </template>
        <div class="co-section-title report-files-heading">
            <div>
                <h3>{{ manager ? '收到的文件' : '我的提交结果' }}</h3>
                <p>
                    {{
                        manager
                            ? '文件名保留提交时的记录，可与团队文件目录核对。'
                            : '这里只展示你的提交记录，文件由收集团队统一保管。'
                    }}
                </p>
            </div>
            <el-button v-if="!manager" :loading="loading" @click="load">刷新结果</el-button>
        </div>
        <el-table :data="submissions" empty-text="还没有提交文件">
            <el-table-column label="文件" min-width="220"
                ><template #default="{ row }"
                    ><div class="co-file-name">
                        <span class="co-file-icon"><co-icon name="file" /></span>
                        <div>
                            <strong>{{ row.filename || '历史文件（名称未保留）' }}</strong><small>{{
                                formatBytes(row.bytes)
                            }}</small>
                        </div>
                    </div></template
                ></el-table-column
            >
            <el-table-column v-if="manager" prop="username" label="提交人" min-width="110" />
            <el-table-column label="提交时间" width="125"
                ><template #default="{ row }">{{
                    formatDate(row.submittedAt)
                }}</template></el-table-column
            >
            <el-table-column label="文件状态" min-width="120"
                ><template #default="{ row }"
                    ><span class="co-status" :class="{ muted: !row.available }">{{
                        row.available ? '已归档' : '已回收或删除'
                    }}</span></template
                ></el-table-column
            >
            <el-table-column v-if="manager" label="操作" width="75"
                ><template #default="{ row }"
                    ><el-button v-if="row.available" link @click="download(row)"
                        >下载</el-button
                    ></template
                ></el-table-column
            >
        </el-table>
    </div>
</template>
<script setup>
import { computed, watch, ref } from 'vue'
import { ElMessage } from 'element-plus'
import CoIcon from '../../../components/cospace/co-icon.vue'
import http from '../../../utils/http'
import { formatBytes, formatDate } from '../../../utils/format'
import { authorizedDownload } from '../../../utils/authorized-download'
const props = defineProps({
    collection: { type: Object, required: true },
    manager: Boolean,
    allowRevoke: Boolean,
})
defineEmits(['revoke'])
const participants = ref([]),
    submissions = ref([]),
    loading = ref(false),
    error = ref(''),
    filter = ref('all')
let request = 0
const filters = [
    { value: 'all', label: '全部人员' },
    { value: 'pending', label: '未提交' },
    { value: 'submitted', label: '已提交' },
]
const activePeople = computed(() => participants.value.filter((item) => item.active))
const filteredPeople = computed(() =>
    participants.value.filter(
        (item) =>
            filter.value === 'all' ||
            (item.active &&
                (filter.value === 'pending' ? !item.submittedCount : item.submittedCount > 0)),
    ),
)
function filesFor(actor) {
    return submissions.value.filter((file) => file.actor === actor)
}
async function load() {
    const version = ++request
    loading.value = true
    error.value = ''
    try {
        const requests = [
            http({
                url: '/collections/' + props.collection.id + '/submissions',
                params: { admin: props.manager },
            }),
        ]
        if (props.manager)
            requests.push(
                http({
                    url:
                        '/workspaces/' +
                        props.collection.workspaceId +
                        '/collections/' +
                        props.collection.id +
                        '/invitees',
                }),
            )
        const results = await Promise.all(requests)
        if (version !== request) return
        submissions.value = results[0].data
        participants.value = props.manager ? results[1].data : []
    } catch (failure) {
        if (version === request) error.value = failure.message || '无法加载提交情况'
    } finally {
        if (version === request) loading.value = false
    }
}
async function download(file) {
    try {
        await authorizedDownload(
            '/workspaces/' + props.collection.workspaceId + '/files/' + file.fileId + '/download',
            file.filename,
        )
    } catch (failure) {
        ElMessage.error(failure.message)
    }
}
defineExpose({ load })
watch(
    () => props.collection.id,
    () => {
        participants.value = []
        submissions.value = []
        load()
    },
    { immediate: true },
)
</script>
<style scoped>
.co-metric em {
    font-size: 14px;
    font-style: normal;
    color: #a2aa96;
    letter-spacing: 0;
}
.co-filter-pills {
    margin-bottom: 14px;
}
.report-person {
    display: flex;
    align-items: center;
    gap: 10px;
}
.report-person .co-avatar {
    width: 28px;
    height: 28px;
    font-size: 11px;
    flex-shrink: 0;
}
.report-person strong {
    font-size: 12px;
    font-weight: 550;
    display: block;
}
.report-person small {
    display: block;
    font-size: 9px;
    color: #a3aa96;
    margin-top: 4px;
}
.report-muted {
    font-size: 11px;
    color: #adb39f;
}
.report-file-list {
    display: flex;
    flex-direction: column;
    gap: 5px;
    font-size: 11px;
    overflow-wrap: anywhere;
}
.report-files-heading {
    margin-top: 32px;
}
.participant-table {
    margin-bottom: 6px;
}
</style>
