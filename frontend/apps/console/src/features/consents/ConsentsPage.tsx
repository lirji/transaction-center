import { NumberedCursorPager } from '../../components/NumberedPager'
import { consentDocumentSchema } from '../../shared/api/schemas'
import { useLocalListPage, useNumberedList, pageItems } from '../../shared/ui/useNumberedList'
import { usePageSize } from '../../shared/ui/usePageSize'
import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Button, Field, PageHeader, Panel, StackForm, DataTable } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'

export function ConsentsPage() {
  const [after, setAfter, page, setPage] = useLocalListPage<number>(0)
  const [pageSize, setPageSize] = usePageSize('consent-documents', 20, () => setAfter(0))
  const [purpose, setPurpose] = useState('')
  const [documentVersion, setDocumentVersion] = useState('')
  const [title, setTitle] = useState('')
  const [contentUrl, setContentUrl] = useState('')
  const [contentHash, setContentHash] = useState('')
  const [formError, setFormError] = useState('')
  const queryClient = useQueryClient()
  const query = useNumberedList({ queryKey: ['consent-documents', after, pageSize], queryFn: () => api.consentDocuments(after, pageSize) }, { path: 'numbered-pages/consent-documents', schema: consentDocumentSchema, after: after, page: page, pageSize: pageSize }, pageItems)
  const publish = useMutation({
    mutationFn: api.publishConsentDocument,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['consent-documents'] })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    if (!purpose.trim() || !documentVersion.trim() || !title.trim()) {
      setFormError('用途、版本和标题不能为空')
      return
    }
    if (!contentUrl.startsWith('https://')) {
      setFormError('发布 URL 必须是 HTTPS')
      return
    }
    if (!/^[0-9a-f]{64}$/.test(contentHash.trim())) {
      setFormError('contentHash 必须是 64 位小写十六进制 SHA-256')
      return
    }
    publish.mutate({
      purpose: purpose.trim(),
      documentVersion: documentVersion.trim(),
      title: title.trim(),
      contentUrl: contentUrl.trim(),
      contentHash: contentHash.trim(),
    })
  }

  return (
    <div className="workspace configuration-workspace">
      <PageHeader
        title="条款文档"
        description="后台发布不可变版本后再给会员展示。服务端不抓取远端正文，也不把列表中的任一版本暗中当成已同意。注册不会自动勾选营销条款。"
      />
      <div className="registry-layout">
        <Panel className="table-panel">
          <h2 className="table-title">条款版本</h2>
          <QueryGate query={query} emptyTitle="尚未发布条款" emptyDetail="发布后会出现在本租户列表。不要按 documentVersion 字符串排序当最新。">
            {(rows) => (
              <>
                <DataTable label="条款版本列表" className="trade-list-table">
                  <thead><tr><th>用途</th><th>版本</th><th>标题</th><th>正文</th><th>SHA-256 摘要</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.id}>
                        <td>{row.purpose}</td>
                        <td className="mono">{row.documentVersion}</td>
                        <td>{row.title}</td>
                        <td><a href={row.contentUrl} target="_blank" rel="noreferrer">查看正文</a></td>
                        <td className="mono">{row.contentHash}</td>
                      </tr>
                    ))}
                  </tbody>
                </DataTable>
              </>
            )}
          </QueryGate>
          <NumberedCursorPager numbered={query.numbered} onNumber={setPage} scope={['consent-documents']} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
            rows={query.isSuccess ? query.data : undefined} getCursor={(row) => row.id} />
        </Panel>
        <Panel className="settings-form">
          <h2>发布新版本</h2>
          <p>确认正文地址与摘要对应后再发布。</p>
          <MutationBanner error={publish.error} success={publish.data ? `已发布 resourceId=${publish.data.resourceId}` : undefined} />
          <StackForm onSubmit={submit} actions={<Button tone="primary" type="submit" loading={publish.isPending}>发布</Button>}>
            <Field label="用途"><input value={purpose} onChange={(event) => setPurpose(event.target.value)} maxLength={64} /></Field>
            <Field label="版本"><input value={documentVersion} onChange={(event) => setDocumentVersion(event.target.value)} maxLength={64} /></Field>
            <Field label="标题"><input value={title} onChange={(event) => setTitle(event.target.value)} maxLength={200} /></Field>
            <Field label="正文 HTTPS 地址" hint="必须 HTTPS，页面应展示与 contentHash 对应的内容。">
              <input value={contentUrl} onChange={(event) => setContentUrl(event.target.value)} maxLength={1000} />
            </Field>
            <Field label="正文 SHA-256 摘要" error={formError}>
              <input value={contentHash} onChange={(event) => setContentHash(event.target.value)} className="mono" />
            </Field>
          </StackForm>
        </Panel>
      </div>
    </div>
  )
}
