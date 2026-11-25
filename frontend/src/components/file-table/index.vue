<template>
    <div class="pan-main-file-list-content">
        <el-table
            ref="fileTable"
            :data="tableData"
            :height="tableHeight"
            tooltip-effect="dark"
            style="width: 100%"
            @selection-change="handleSelectionChange"
            @cell-mouse-enter="showOperation"
            @cell-mouse-leave="hiddenOperation"
        >
            <el-table-column type="selection" width="55"> </el-table-column>
            <el-table-column
                label="文件名"
                prop="filename"
                sortable
                show-overflow-tooltip
                min-width="260"
            >
                <template #default="scope">
                    <div @click="clickFilename(scope.row)" class="file-name-content">
                        <i
                            :class="getFileFontElement(scope.row.fileType)"
                            style="margin-right: 15px; font-size: 20px; cursor: pointer"
                        />
                        <span style="cursor: pointer">{{ scope.row.filename }}</span>
                    </div>
                    <div
                        v-show="scope.row.fileId === operationShowFileId"
                        class="file-operation-content"
                    >
                        <el-tooltip class="item" effect="light" content="下载" placement="top">
                            <download-button
                                size="small"
                                :circle-flag="true"
                                :item="scope.row"
                                @refresh="refresh"
                            />
                        </el-tooltip>
                        <el-tooltip class="item" effect="light" content="重命名" placement="top">
                            <rename-button
                                size="small"
                                :circle-flag="true"
                                :item="scope.row"
                                @refresh="refresh"
                            />
                        </el-tooltip>
                        <el-tooltip class="item" effect="light" content="删除" placement="top">
                            <delete-button
                                size="small"
                                :circle-flag="true"
                                :item="scope.row"
                                @refresh="refresh"
                                :parent-id="parentId"
                            />
                        </el-tooltip>
                        <el-tooltip class="item" effect="light" content="分享" placement="top">
                            <share-button size="small" :circle-flag="true" :item="scope.row" />
                        </el-tooltip>
                        <el-tooltip class="item" effect="light" content="复制到" placement="top">
                            <copy-button
                                size="small"
                                :circle-flag="true"
                                :item="scope.row"
                                @refresh="refresh"
                                :parent-id="parentId"
                            />
                        </el-tooltip>
                        <el-tooltip class="item" effect="light" content="移动到" placement="top">
                            <transfer-button
                                size="small"
                                :circle-flag="true"
                                :item="scope.row"
                                @refresh="refresh"
                                :parent-id="parentId"
                            />
                        </el-tooltip>
                    </div>
                </template>
            </el-table-column>
            <el-table-column
                prop="fileSizeDesc"
                sortable
                label="大小"
                min-width="120"
                align="center"
                :formatter="formatFileSize"
            >
            </el-table-column>
            <el-table-column
                prop="updateTime"
                sortable
                align="center"
                label="修改日期"
                min-width="150"
                :formatter="formatUpdateTime"
            >
            </el-table-column>
            <template #empty
                ><div class="co-empty">
                    <div class="co-empty-icon"><co-icon name="folder" /></div>
                    <h3>这里还没有文件</h3>
                    <p>上传一份资料，或创建文件夹开始整理。</p>
                </div></template
            >
        </el-table>
        <el-image-viewer v-if="showViewer" @close="closeShowViewer" :url-list="[imgUrl]" />
    </div>
</template>

<script>
import CoIcon from '../cospace/co-icon.vue'
import DownloadButton from '../buttons/download-button/index.vue'
import DeleteButton from '../buttons/delete-button/index.vue'
import RenameButton from '../buttons/rename-button/index.vue'
import CopyButton from '../buttons/copy-button/index.vue'
import TransferButton from '../buttons/transfer-button/index.vue'
import ShareButton from '../buttons/share-button/index.vue'
import fileService from '../../api/file'
import panUtil from '../../utils/common'
import { ElImageViewer } from 'element-plus'

export default {
    name: 'FileTable',
    components: {
        CoIcon,
        DownloadButton,
        DeleteButton,
        RenameButton,
        CopyButton,
        TransferButton,
        ShareButton,
        ElImageViewer,
    },
    props: {
        tableData: Array,
        fileTypes: String,
        parentId: String,
    },
    data() {
        return {
            tableHeight: Math.max(260, window.innerHeight - 300),
            operationShowFileId: '',
            showViewer: false,
            imgUrl: '',
        }
    },
    methods: {
        handleSelectionChange(multipleSelection) {
            this.$emit('handleSelectionChange', multipleSelection)
        },
        showOperation(row, column, cell, event) {
            this.operationShowFileId = row.fileId
        },
        hiddenOperation(row, column, cell, event) {
            this.operationShowFileId = ''
        },
        clickFilename(row) {
            let _this = this
            switch (row.fileType) {
                case 0:
                    _this.goInFolder(row)
                    break
                case 3:
                case 4:
                case 10:
                    _this.showOffice(row)
                    break
                case 5:
                case 6:
                    _this.showIframe(row)
                    break
                case 7:
                    _this.showImg(row)
                    break
                case 8:
                    _this.showMusic(row)
                    break
                case 9:
                    _this.showVideo(row)
                    break
                case 11:
                    _this.showCode(row)
                default:
                    break
            }
        },
        goInFolder(row) {
            let _this = this
            fileService.getBreadcrumbs(
                {
                    fileId: row.fileId,
                },
                (res) => {
                    _this.breadcrumbs = res.data
                    _this.$emit('goInFolder', res.data)
                    _this.loadTableData(row.fileId)
                },
                (res) => {
                    _this.$message.error(res.message)
                },
            )
        },
        loadTableData(parentId) {
            let _this = this
            fileService.list(
                {
                    parentId: parentId,
                    fileTypes: _this.fileTypes,
                },
                (res) => {
                    _this.$emit('refresh', res.data)
                },
                (res) => {
                    _this.$message.error(res.message)
                },
            )
        },
        refresh(fileList) {
            this.$emit('refresh', fileList)
        },
        showOffice(row) {
            this.openNewPage('/preview/office', 'PreviewOffice', {
                fileId: row.fileId,
            })
        },
        showIframe(row) {
            this.openNewPage('/preview/iframe', 'PreviewIframe', {
                fileId: row.fileId,
            })
        },
        showImg(row) {
            this.imgUrl = panUtil.getPreviewUrl(row.fileId)
            this.showViewer = true
        },
        showMusic(row) {
            this.openNewPage('/preview/music', 'PreviewMusic', {
                fileId: row.fileId,
                parentId: row.parentId,
            })
        },
        showVideo(row) {
            this.openNewPage('/preview/video', 'PreviewVideo', {
                fileId: row.fileId,
                parentId: row.parentId,
            })
        },
        showCode(row) {
            this.openNewPage('/preview/code', 'PreviewCode', {
                fileId: row.fileId,
            })
        },
        openNewPage(path, name, params) {
            const { href } = this.$router.resolve({
                path: path,
                name: name,
                params: params,
            })
            window.open(href, '_blank')
        },
        getFileFontElement(type) {
            let tagStr = 'fa fa-file'
            switch (type) {
                case 0:
                    tagStr = 'fa fa-folder-o'
                    break
                case 2:
                    tagStr = 'fa fa-file-archive-o'
                    break
                case 3:
                    tagStr = 'fa fa-file-excel-o'
                    break
                case 4:
                    tagStr = 'fa fa-file-word-o'
                    break
                case 5:
                    tagStr = 'fa fa-file-pdf-o'
                    break
                case 6:
                    tagStr = 'fa fa-file-text-o'
                    break
                case 7:
                    tagStr = 'fa fa-file-image-o'
                    break
                case 8:
                    tagStr = 'fa fa-file-audio-o'
                    break
                case 9:
                    tagStr = 'fa fa-file-video-o'
                    break
                case 10:
                    tagStr = 'fa fa-file-powerpoint-o'
                    break
                case 11:
                    tagStr = 'fa fa-file-code-o'
                    break
                default:
                    break
            }
            return tagStr
        },
        formatFileSize(row, column, cellValue, index) {
            if (row.fileSizeDesc) {
                return row.fileSizeDesc
            }
            return '--'
        },
        formatUpdateTime(row, column, cellValue, index) {
            return panUtil.formatTime(new Date(row.updateTime))
        },
        closeShowViewer() {
            this.showViewer = false
        },
    },
    computed: {},
    mounted() {},
    watch: {},
}
</script>

<style>
.pan-main-file-list-content {
    width: 100%;
    height: 100%;
    margin-top: 20px;
}

.file-operation-content {
    display: inline-block;
    position: absolute;
    right: 100px;
    top: 8px;
}
</style>
