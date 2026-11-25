<template>
    <auth-layout>
        <span class="co-eyebrow">LET’S GET YOU BACK IN</span>
        <h2 class="auth-title">找回你的账号</h2>
        <p class="auth-description">通过密保验证，重新设置密码。</p>
        <ol class="auth-steps">
            <li
                v-for="(label, index) in ['确认账号', '密保验证', '重设密码']"
                :key="label"
                :class="{ active: step >= index }"
            >
                <span>{{ index + 1 }}</span>{{ label }}
            </li>
        </ol>
        <form class="auth-fields" @submit.prevent="next">
            <label v-if="step === 0" for="reset-username"
                >用户名<input
                    id="reset-username"
                    v-model.trim="username"
                    autocomplete="username"
                    placeholder="输入需要找回的用户名"
                    required
            /></label>
            <template v-else-if="step === 1"
                ><p class="auth-question">{{ question }}</p>
                <label for="reset-answer"
                    >密保答案<input
                        id="reset-answer"
                        v-model.trim="answer"
                        placeholder="输入你的答案"
                        required /></label
            ></template>
            <template v-else
                ><label for="reset-password"
                    >新密码<input
                        id="reset-password"
                        v-model="password"
                        type="password"
                        autocomplete="new-password"
                        placeholder="8–16 位新密码"
                        required /></label
                ><label for="reset-confirm"
                    >确认新密码<input
                        id="reset-confirm"
                        v-model="confirmation"
                        type="password"
                        autocomplete="new-password"
                        placeholder="再次输入新密码"
                        required /></label
            ></template>
            <p v-if="error" class="co-error" role="alert">{{ error }}</p>
            <el-button class="auth-submit" type="primary" native-type="submit" :loading="loading">{{
                step === 2 ? '重设密码' : '下一步'
            }}</el-button>
        </form>
        <p class="auth-switch">
            <router-link to="/login">返回登录</router-link
            ><button v-if="step" class="co-text-button" @click="restart">重新验证</button>
        </p>
    </auth-layout>
</template>
<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthLayout from '../../components/cospace/auth-layout.vue'
import http from '../../utils/http'
import panUtil from '../../utils/common'
const router = useRouter(),
    step = ref(0),
    username = ref(''),
    question = ref(''),
    answer = ref('')
const password = ref(''),
    confirmation = ref(''),
    token = ref(''),
    error = ref(''),
    loading = ref(false)
function restart() {
    step.value = 0
    token.value = ''
    password.value = ''
    confirmation.value = ''
    answer.value = ''
    error.value = ''
}
async function next() {
    if (loading.value) return
    error.value = ''
    loading.value = true
    try {
        if (step.value === 0) {
            question.value = (
                await http({
                    url: '/user/username/check',
                    method: 'post',
                    data: { username: username.value },
                })
            ).data
            step.value = 1
        } else if (step.value === 1) {
            token.value = (
                await http({
                    url: '/user/answer/check',
                    method: 'post',
                    data: { username: username.value, question: question.value, answer: answer.value },
                })
            ).data
            step.value = 2
        } else {
            if (!panUtil.checkPassword(password.value)) throw new Error('密码需为 8–16 位')
            if (password.value !== confirmation.value) throw new Error('两次密码不一致')
            await http({
                url: '/user/password/reset',
                method: 'post',
                data: { username: username.value, newPassword: password.value, token: token.value },
            })
            ElMessage.success('密码已重设，请重新登录')
            await router.replace('/login')
        }
    } catch (failure) {
        error.value = failure.message || '验证失败，请重试'
    } finally {
        loading.value = false
    }
}
</script>
