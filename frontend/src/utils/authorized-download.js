import panUtil from './common'
import { getToken } from './cookie'
export async function authorizedDownload(path, filename) {
    const response = await fetch(panUtil.getUrlPrefix() + path, {
        headers: { Authorization: getToken() },
    })
    if (!response.ok || !response.headers.get('content-disposition'))
        throw new Error('文件不可下载，可能已删除或访问权限发生变化')
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = url
    link.download = filename || 'download'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 60000)
}
