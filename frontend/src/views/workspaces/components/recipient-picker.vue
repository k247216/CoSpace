<template>
    <el-select
        v-model="selection"
        multiple
        filterable
        remote
        :remote-method="search"
        :loading="loading"
        placeholder="选择提交人，或搜索完整用户名 / ID"
        style="width: 100%"
        @visible-change="open"
    >
        <el-option
            v-for="user in options"
            :key="user.id"
            :value="user.id"
            :label="user.username + '（' + user.id + '）'"
        />
    </el-select>
</template>
<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../../../utils/http'
const props = defineProps({
    workspaceId: { type: String, required: true },
    modelValue: { type: Array, required: true },
})
const emit = defineEmits(['update:modelValue'])
const selection = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value),
})
const options = ref([]),
    loading = ref(false)
let request = 0
async function search(query = '') {
    const version = ++request
    loading.value = true
    try {
        const { data } = await http({
            url: '/workspaces/' + props.workspaceId + '/collections/recipients',
            params: { query },
        })
        if (version !== request) return
        options.value = [
            ...options.value.filter((user) => selection.value.includes(user.id)),
            ...data,
        ].filter((user, index, users) => users.findIndex((item) => item.id === user.id) === index)
    } catch (failure) {
        if (version === request) ElMessage.error(failure.message || '无法查询用户')
    } finally {
        if (version === request) loading.value = false
    }
}
function open(visible) {
    if (visible) search()
}
</script>
