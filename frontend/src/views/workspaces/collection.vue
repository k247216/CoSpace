<template>
    <section class="collection-page">
        <router-link to="/collections" class="collection-back">← 返回我的收集</router-link>
        <p v-if="error" class="co-error" role="alert">{{ error }}</p>
        <template v-if="collection">
            <div class="co-page-heading">
                <div>
                    <h2>{{ collection.title }}</h2>
                    <p>
                        截止 {{ new Date(collection.deadline).toLocaleString() }} · 单文件上限
                        {{ formatBytes(collection.maxFileBytes) }} · 文件类型
                        {{ collection.allowedSuffix || '不限' }}
                    </p>
                </div>
                <span class="co-status" :class="{ muted: ended }">{{
                    ended ? '收集已结束' : '收集中'
                }}</span>
            </div>
            <div class="collection-submit-banner">
                <div class="collection-submit-icon"><co-icon name="inbox" /></div>
                <div>
                    <h3>
                        {{
                            collection.canSubmit
                                ? '将资料交到正确的地方'
                                : collection.canManage
                                  ? '掌握每一份资料的交付进度'
                                  : '你的提交记录，已为你保留'
                        }}
                    </h3>
                    <p v-if="collection.canSubmit">
                        文件将直接归档到团队指定目录。上传完成后，可在下方核对提交结果。
                    </p>
                    <p v-else-if="ended">收集已结束，已有提交记录仍可查看。</p>
                    <p v-else-if="collection.canManage">
                        你正在查看管理视图。提交文件仍需被加入受邀名单。
                    </p>
                    <p v-else>当前账号无法继续提交。如有历史提交，可在下方查看。</p>
                </div>
                <upload-button
                    v-if="collection.canSubmit"
                    :round-flag="true"
                    size="default"
                    :parent-id="collection.parentId"
                    :workspace-id="collection.workspaceId"
                    :collection-id="collection.id"
                    @refresh="load"
                />
            </div>
            <div class="co-card">
                <collection-report
                    ref="report"
                    :key="collection.id"
                    :collection="collection"
                    :manager="collection.canManage"
                />
            </div>
        </template>
    </section>
</template>
<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../../utils/http'
import { formatBytes } from '../../utils/format'
import CoIcon from '../../components/cospace/co-icon.vue'
import UploadButton from '../../components/buttons/upload-button/index.vue'
import CollectionReport from './components/collection-report.vue'
const route = useRoute(),
    collection = ref(null),
    report = ref(null),
    error = ref('')
const ended = computed(
    () => collection.value?.closed || new Date(collection.value?.deadline).getTime() <= Date.now(),
)
async function load() {
    error.value = ''
    try {
        collection.value = (await http({ url: '/collections/' + route.params.collection })).data
        await nextTick()
        await report.value?.load()
    } catch (failure) {
        collection.value = null
        error.value = failure.message || '无法加载收集任务'
    }
}
onMounted(load)
</script>
<style scoped>
.collection-back {
    display: inline-block;
    font-size: 11px;
    color: #8b9781;
    margin-bottom: 26px;
}
.collection-submit-banner {
    display: flex;
    align-items: center;
    gap: 18px;
    padding: 24px;
    background: #ecefe2;
    border: 1px solid #e0e6d5;
    border-radius: 12px;
    margin-bottom: 25px;
}
.collection-submit-banner h3 {
    font-size: 14px;
    margin-bottom: 7px;
}
.collection-submit-banner p {
    font-size: 11px;
    line-height: 1.8;
    color: #869476;
    margin: 0;
}
.collection-submit-banner > .el-button,
.collection-submit-banner > :last-child {
    flex-shrink: 0;
}
.collection-submit-banner > :nth-child(2) {
    flex: 1;
}
.collection-submit-icon {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    background: #e0e8d3;
    color: #7e9269;
    border-radius: 12px;
    flex-shrink: 0;
}
.collection-submit-icon svg {
    width: 24px;
    height: 24px;
}
@media (max-width: 760px) {
    .collection-submit-banner {
        align-items: flex-start;
        flex-wrap: wrap;
        padding: 20px 17px;
    }
    .collection-submit-banner > :nth-child(2) {
        min-width: 200px;
    }
    .collection-submit-banner > :last-child {
        margin-left: 0;
    }
    .co-page-heading p {
        max-width: 320px;
    }
}
</style>
