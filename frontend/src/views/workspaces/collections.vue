<template>
    <section class="collections-page">
        <div class="co-page-heading">
            <div>
                <h2>我的收集</h2>
            </div>
            <el-button :loading="loading" @click="load">刷新列表</el-button>
        </div>
        <div class="co-metrics">
            <div class="co-metric">
                <span>等待你提交</span
                ><strong>{{
                    tasks.filter((task) => task.canSubmit && !task.submittedCount).length
                }}</strong
                ><small>进行中的有效收集任务</small>
            </div>
            <div class="co-metric">
                <span>已参与收集</span
                ><strong>{{ tasks.filter((task) => task.submittedCount > 0).length }}</strong
                ><small>有提交记录的任务</small>
            </div>
            <div class="co-metric">
                <span>我提交的文件</span
                ><strong>{{ tasks.reduce((sum, task) => sum + task.submittedCount, 0) }}</strong
                ><small>文件由各收集团队保管</small>
            </div>
        </div>
        <div class="co-card">
            <div class="co-section-title">
                <div>
                    <h3>收到的邀请</h3>
                    <p>进入任务查看要求，提交后仅能查看自己的结果。</p>
                </div>
            </div>
            <p v-if="error" role="alert">{{ error }}</p>
            <el-table :data="tasks" empty-text="暂无收集邀请">
                <el-table-column label="收集名称" min-width="190"
                    ><template #default="{ row }"
                        ><div class="co-file-name">
                            <span class="co-file-icon"><co-icon name="inbox" /></span
                            ><strong>{{ row.title }}</strong>
                        </div></template
                    ></el-table-column
                >
                <el-table-column prop="workspaceName" label="来源团队" min-width="140" />
                <el-table-column label="截止时间"
                    ><template #default="{ row }">{{
                        new Date(row.deadline).toLocaleString()
                    }}</template></el-table-column
                >
                <el-table-column label="状态"
                    ><template #default="{ row }"
                        ><span class="co-status" :class="{ muted: !row.canSubmit }">{{
                            status(row)
                        }}</span></template
                    ></el-table-column
                >
                <el-table-column prop="submittedCount" label="已提交文件数" />
                <el-table-column label="操作"
                    ><template #default="{ row }"
                        ><router-link :to="'/collections/' + row.id">{{
                            row.canSubmit ? '进入提交' : '查看结果'
                        }}</router-link></template
                    ></el-table-column
                >
            </el-table>
        </div>
    </section>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import http from '../../utils/http'
import CoIcon from '../../components/cospace/co-icon.vue'
import { formatDate } from '../../utils/format'
const tasks = ref([]),
    loading = ref(false),
    error = ref('')
function status(task) {
    if (!task.invited) return '邀请已撤销'
    if (task.closed) return '已关闭'
    if (new Date(task.deadline).getTime() <= Date.now()) return '已截止'
    return task.canSubmit ? '可提交' : '提交资格不可用'
}
async function load() {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
        tasks.value = (await http({ url: '/collections' })).data
    } catch (failure) {
        tasks.value = []
        error.value = failure.message || '无法加载收集邀请'
    } finally {
        loading.value = false
    }
}
onMounted(load)
</script>
<style scoped>
[role='alert'] {
    color: #ac5748;
}
</style>
