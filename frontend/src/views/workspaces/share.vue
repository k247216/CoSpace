<template>
    <section class="share-page co-card">
        <div class="co-page-heading">
            <div>
                <h2>有一份资料，与你分享</h2>
                <p>使用指定接收人的账号下载，访问权限由团队管理。</p>
            </div>
        </div>
        <p v-if="error" role="alert">{{ error }}</p>
        <el-button type="primary" :loading="busy" @click="download">下载文件</el-button>
    </section>
</template>
<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { getToken } from '../../utils/cookie'
import panUtil from '../../utils/common'
const route = useRoute(),
    error = ref(''),
    busy = ref(false)
async function download() {
    error.value = ''
    busy.value = true
    try {
        const response = await fetch(
            panUtil.getUrlPrefix() + '/workspace-shares/' + route.params.share + '/download',
            { headers: { Authorization: getToken() } },
        )
        if (!response.ok || !response.headers.get('content-disposition'))
            throw new Error('分享已到期、撤销，或未授权给当前账号')
        const url = URL.createObjectURL(await response.blob())
        const link = document.createElement('a')
        link.href = url
        link.download = 'shared-file'
        link.click()
        setTimeout(() => URL.revokeObjectURL(url), 60000)
    } catch (failure) {
        error.value = failure.message || '下载未完成'
    } finally {
        busy.value = false
    }
}
</script>
<style scoped>
.share-page {
    padding: 36px;
    max-width: 720px;
    margin: 30px auto;
}
[role='alert'] {
    color: #b42318;
}
</style>
