<template>
    <div class="upload-button-content">
        <input ref="picker" class="upload-file-picker" type="file" multiple @change="selectFiles" />
        <el-button
            v-if="roundFlag"
            type="primary"
            :size="size"
            round
            :disabled="busy || !parentId"
            @click="chooseFiles"
        >
            上传<co-icon name="upload" class="upload-icon" />
        </el-button>
        <el-button
            v-if="circleFlag"
            :size="size"
            circle
            :disabled="busy || !parentId"
            aria-label="上传文件"
            @click="chooseFiles"
        >
            <co-icon name="upload" />
        </el-button>
        <el-dialog
            title="文件上传"
            :show-close="!busy"
            :close-on-click-modal="false"
            :close-on-press-escape="!busy"
            v-model="uploadProcessDialogVisible"
            :append-to-body="true"
            width="640px"
        >
            <p class="upload-resume-tip">上传失败可重试；刷新页面后，重新选择同一文件可继续上传。</p>
            <div class="upload-process-content">
                <div v-for="file in fileList" :key="file.id" class="upload-process-item-content">
                    <div class="upload-item-file-name" :title="file.filename">{{ file.filename }}</div>
                    <el-progress :percentage="file.percent" :status="file.status" />
                    <div class="upload-item-stage" role="status">{{ file.stage }}</div>
                    <div v-if="file.error" class="upload-item-error" role="alert">{{ file.error }}</div>
                    <el-button
                        v-if="file.status === 'exception' && !file.cancelled && !file.cancelFailed"
                        size="small"
                        :disabled="busy || file.retryAt > now"
                        @click="retry(file)"
                    >
                        {{
                            file.retryAt > now
                                ? '等待 ' + Math.ceil((file.retryAt - now) / 1000) + ' 秒'
                                : '重试'
                        }}
                    </el-button>
                    <el-button
                        v-if="file.uploadId && !file.done && !file.cancelled"
                        size="small"
                        :disabled="
                            file.cancelRequested ||
                            (busy && !file.running) ||
                            file.stage === '正在处理文件'
                        "
                        @click="cancel(file)"
                        >{{ file.cancelRequested ? '正在取消' : '取消上传' }}</el-button
                    >
                </div>
            </div>
            <template #footer
                ><span
                    ><el-button :disabled="busy" @click="uploadProcessDialogVisible = false"
                        >关闭</el-button
                    ></span
                ></template
            >
        </el-dialog>
    </div>
</template>

<script>
import CoIcon from '../../cospace/co-icon.vue'
import panUtil from '../../../utils/common'
import { MD5 } from '../../md5'
import api from '../../../api/upload'
import http from '../../../utils/http'
import { uploadFile } from '../../../utils/resumable-upload'
import { createPendingStore } from '../../../utils/upload-pending'

export default {
    name: 'uploadButton',
    components: { CoIcon },
    props: {
        roundFlag: Boolean,
        circleFlag: Boolean,
        size: String,
        parentId: String,
        workspaceId: String,
        collectionId: String,
    },
    data() {
        return {
            uploadProcessDialogVisible: false,
            fileList: [],
            busy: false,
            nextId: 0,
            now: Date.now(),
        }
    },
    mounted() {
        this.clock = setInterval(() => {
            this.now = Date.now()
        }, 1000)
    },
    beforeUnmount() {
        clearInterval(this.clock)
    },
    methods: {
        chooseFiles() {
            this.$refs.picker.click()
        },
        async selectFiles(event) {
            const files = Array.from(event.target.files || [])
            event.target.value = '' // 同一文件失败后可以重新选择。
            if (!files.length || this.busy || !this.parentId) return
            this.fileList = files.map((file) => ({
                id: ++this.nextId,
                file,
                filename: file.name,
                parentId: this.parentId, // 选择时固定目录，重试不会随页面目录变化。
                md5: null,
                uploadId: null,
                percent: 0,
                status: undefined,
                stage: '等待上传',
                error: '',
                done: false,
                retryAt: 0,
                running: false,
                cancelled: false,
                cancelRequested: false,
                cancelFailed: false,
            }))
            this.uploadProcessDialogVisible = true
            await this.run(this.fileList)
        },
        async retry(item) {
            if (item.retryAt <= Date.now()) await this.run([item])
        },
        async cancel(item) {
            if (item.running) {
                item.cancelRequested = true
                return
            }
            this.busy = true
            try {
                await api.cancel(item.uploadId)
                createPendingStore(this.collectionId || this.workspaceId || '').remove(
                    JSON.stringify({
                        name: item.file.name,
                        size: item.file.size,
                        md5: item.md5,
                        parentId: item.parentId,
                    }),
                )
                item.uploadId = null
                item.requestId = null
                item.cancelled = true
                item.cancelFailed = false
                item.error = ''
                item.stage = '已取消，预占空间已释放'
            } catch (error) {
                item.cancelFailed = true
                item.error = error.message || '取消未确认，请重试取消'
            } finally {
                this.busy = false
            }
        },
        async run(items) {
            this.busy = true
            let completed = 0
            const store = createPendingStore(this.collectionId || this.workspaceId || '')
            const workspace = this.workspaceId
            const collection = this.collectionId
            const uploadApi = workspace
                ? {
                      ...api,
                      instant: async () => {
                          throw { status: 1, message: '秒传未命中，请上传文件' }
                      },
                      initialize: (data) =>
                          http({
                              url: collection
                                  ? '/collections/' + collection + '/uploads'
                                  : '/workspaces/' + workspace + '/files/uploads',
                              method: 'post',
                              data: { ...data, directUpload: true },
                          }),
                  }
                : api
            try {
                for (const item of items) {
                    item.running = true
                    try {
                        if (item.file.size > panUtil.getMaxFileSize())
                            throw new Error('文件大小超过3GB限制')
                        await uploadFile(item, {
                            api: uploadApi,
                            store,
                            hashFile: (file) =>
                                new Promise((resolve, reject) =>
                                    MD5(file, (error, hash) => {
                                        if (error) reject(new Error('读取文件失败，请重新选择文件'))
                                        else resolve(hash)
                                    }),
                                ),
                            update: (fields) => Object.assign(item, fields),
                        })
                        if (item.done) completed++
                    } catch (error) {
                        item.status = 'exception'
                        item.cancelFailed = item.cancelRequested
                        item.cancelRequested = false
                        item.stage = item.cancelFailed ? '取消未确认，请重试取消' : '上传未完成'
                        item.error = (error && error.message) || '请求失败，请检查网络后重试'
                        this.now = Date.now()
                        item.retryAt = error.retryAfterSeconds
                            ? this.now + error.retryAfterSeconds * 1000
                            : 0
                    } finally {
                        item.running = false
                    }
                }
                if (completed) this.$emit('refresh', undefined)
                if (items.every((item) => item.done)) this.$message.success('上传完成')
            } finally {
                this.busy = false
                window.dispatchEvent(new Event('rpan-space-changed'))
            }
        },
    },
}
</script>

<style scoped>
.upload-icon {
    width: 14px;
    height: 14px;
    margin-left: 7px;
}
.upload-button-content {
    display: inline-block;
    margin-right: 10px;
}
.upload-file-picker {
    display: none;
}
.upload-resume-tip {
    margin: 0 0 20px;
    color: #606266;
    line-height: 1.6;
}
.upload-process-item-content {
    margin-bottom: 24px;
}
.upload-item-file-name {
    overflow-wrap: anywhere;
    margin-bottom: 10px;
    color: #303133;
}
.upload-item-stage {
    margin: 6px 0;
    color: #606266;
}
.upload-item-error {
    margin: 6px 0;
    color: #f56c6c;
    overflow-wrap: anywhere;
}
</style>
