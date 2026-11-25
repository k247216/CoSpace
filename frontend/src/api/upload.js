import http from '../utils/http'
import SparkMD5 from 'spark-md5'

function putPart(signature, blob, onUploadProgress) {
    return new Promise((resolve, reject) => {
        const request = new XMLHttpRequest()
        request.open('PUT', signature.url)
        request.timeout = 120000
        for (const [name, value] of Object.entries(signature.headers))
            request.setRequestHeader(name, value)
        request.upload.onprogress = onUploadProgress
        request.onload = () => {
            if (request.status >= 200 && request.status < 300) return resolve()
            reject(Object.assign(new Error('OSS 未接收分片，请重试'), { httpStatus: request.status }))
        }
        request.ontimeout = () => reject(new Error('OSS 分片上传超时，请重试'))
        request.onerror = () => reject(new Error('无法连接 OSS，请检查网络后重试'))
        request.send(blob)
    })
}

async function signPart(id, index, md5) {
    const reply = await http({
        url: '/file/upload/tasks/' + id + '/chunks/' + index + '/signature',
        method: 'post',
        data: { md5 },
    })
    return reply.data
}

// 返回Promise，便于按“初始化 -> 补片 -> 完成”的顺序编排。
export default {
    instant(data) {
        return http({ url: '/file/sec-upload', method: 'post', data })
    },
    initialize(data) {
        return http({ url: '/file/upload/tasks', method: 'post', data: { ...data, directUpload: true } })
    },
    status(id) {
        return http({ url: '/file/upload/tasks/' + id, method: 'get' })
    },
    cancel(id) {
        return http({ url: '/file/upload/tasks/' + id + '/cancel', method: 'post' })
    },
    chunk(id, index, blob, onUploadProgress) {
        const data = new FormData()
        data.append('file', blob, 'chunk.part')
        return http({
            url: '/file/upload/tasks/' + id + '/chunks/' + index,
            method: 'post',
            data,
            onUploadProgress,
            timeout: 120000,
        })
    },
    async directChunk(id, index, blob, onUploadProgress) {
        const md5 = SparkMD5.ArrayBuffer.hash(await blob.arrayBuffer())
        const signature = await signPart(id, index, md5)
        try {
            await putPart(signature, blob, onUploadProgress)
        } catch (error) {
            if (error.httpStatus !== 403) throw error
            await putPart(await signPart(id, index, md5), blob, onUploadProgress)
        }
    },
    complete(id) {
        return http({ url: '/file/upload/tasks/' + id + '/complete', method: 'post', timeout: 600000 })
    },
}
