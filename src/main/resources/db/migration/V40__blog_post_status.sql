-- Blog articles can be saved as drafts. blog_posts.status already existed but
-- only ever held '0' and nothing read it; it now says DRAFT or PUBLISHED. Every
-- article written before drafts existed was live on the site, so it is PUBLISHED.
UPDATE blog_posts SET status = 'PUBLISHED' WHERE status IS NULL OR status <> 'DRAFT';
