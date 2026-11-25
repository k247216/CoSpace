'use strict'

import axios from 'axios'
import { clearToken, getToken } from './cookie'
import { ElMessageBox as MessageBox, ElMessage as Message } from 'element-plus'
import panUtil from './common'

// Each tab keeps its own session snapshot so stale pages cannot act as another account.
let pageToken = getToken()

const toLogin = function () {
        // to re-login
        MessageBox.confirm('您需要重新登陆', '确认退出登录', {
            confirmButtonText: '重新登陆',
            cancelButtonText: '取消',
            type: 'warning',
        }).then(() => {
            clearToken()
            window.location.reload()
        })
    },
    http = axios.create({
        baseURL: panUtil.getUrlPrefix(),
        timeout: 1000 * 30,
        headers: {
            'Content-Type': 'application/json;charset=utf-8',
        },
    })

http.interceptors.request.use(
    (config) => {
        const token = getToken()
        if (token && pageToken && token !== pageToken) {
            throw new Error('登录账号已在其他页面切换，请刷新当前页面后重新操作')
        }
        if (token) pageToken = token
        if (config.data instanceof FormData) {
            // 让浏览器生成multipart边界，不能把分片FormData序列化为JSON。
            config.headers.delete('Content-Type')
        } else if (config.data) {
            config.data = JSON.stringify(config.data)
        }
        if (token) {
            config.headers['Authorization'] = token
        }
        return config
    },
    (error) => {
        Message.error('请求失败')
        console.error(error)
        return Promise.reject(error)
    },
)

http.interceptors.response.use(
    (res) => {
        if (res.data.status === 10) {
            toLogin()
            res.data.message = '您需要重新登陆'
            return Promise.reject(res.data)
        }
        if (res.data.status !== 0) {
            return Promise.reject(res.data)
        }
        if (res.config.url === '/user/login' || res.config.url === '/user/exit') pageToken = ''
        if (res.config.method !== 'get' && !/\/chunks\/\d+/.test(res.config.url)) {
            window.dispatchEvent(new Event('rpan-space-changed'))
        }
        return res.data
    },
    (error) => {
        const response = error.response
        const failure =
            response && response.data && typeof response.data === 'object' ? response.data : error
        failure.httpStatus = response && response.status
        if (response && response.headers) {
            const seconds = Number(response.headers['retry-after'])
            if (Number.isFinite(seconds) && seconds > 0) failure.retryAfterSeconds = seconds
        }
        if (!failure.message) failure.message = '网络请求失败，请稍后重试'
        Message.error(failure.message)
        return Promise.reject(failure)
    },
)

export default http
