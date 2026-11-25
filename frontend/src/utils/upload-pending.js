import SparkMD5 from 'spark-md5'
import { getToken } from './cookie'

// 按当前登录会话隔离；只保存参数对应的任务ID，不保存文件内容或登录凭证。
export function createPendingStore(namespace = '') {
    const prefix =
        'rpan-upload-v1:' +
        SparkMD5.hash(getToken()) +
        ':' +
        (namespace ? SparkMD5.hash(namespace) + ':' : '')
    const storageKey = (key) => prefix + SparkMD5.hash(key)
    return {
        get(key) {
            try {
                return localStorage.getItem(storageKey(key))
            } catch (e) {
                return null
            }
        },
        set(key, id) {
            try {
                localStorage.setItem(storageKey(key), id)
            } catch (e) {}
        },
        remove(key) {
            try {
                localStorage.removeItem(storageKey(key))
                localStorage.removeItem(storageKey(key) + ':request')
            } catch (e) {}
        },
        getRequest(key) {
            try {
                return localStorage.getItem(storageKey(key) + ':request')
            } catch (e) {
                return null
            }
        },
        setRequest(key, id) {
            try {
                localStorage.setItem(storageKey(key) + ':request', id)
            } catch (e) {}
        },
    }
}
