from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

OUT = r"D:\Nexzian\deliverables\Nexzian Capabilities Brief.docx"
NAVY = "142138"; GOLD = "B89552"; PALE = "F4F1EA"; LIGHT = "E5E9EF"; SLATE = "59677C"

def shade(cell, color):
    tcPr = cell._tc.get_or_add_tcPr(); shd = OxmlElement('w:shd'); shd.set(qn('w:fill'), color); tcPr.append(shd)
def border(cell, color=LIGHT):
    tcPr = cell._tc.get_or_add_tcPr(); borders = OxmlElement('w:tcBorders')
    for edge in ('top','left','bottom','right'):
        el = OxmlElement(f'w:{edge}'); el.set(qn('w:val'),'single'); el.set(qn('w:sz'),'6'); el.set(qn('w:color'),color); borders.append(el)
    tcPr.append(borders)
def set_cell_text(cell, text, bold=False, color=None, size=9.5):
    cell.text = ''; p = cell.paragraphs[0]; p.paragraph_format.space_after = Pt(2)
    r = p.add_run(text); r.bold = bold; r.font.size = Pt(size); r.font.name = 'Aptos';
    if color: r.font.color.rgb = RGBColor.from_string(color)
    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
def add_title(doc, text, subtitle=None):
    p=doc.add_paragraph(style='Title'); p.alignment=WD_ALIGN_PARAGRAPH.LEFT
    r=p.add_run(text); r.font.name='Aptos Display'; r.font.size=Pt(28); r.font.color.rgb=RGBColor(0,0,0)
    if subtitle:
        p=doc.add_paragraph(); p.paragraph_format.space_after=Pt(22); r=p.add_run(subtitle); r.font.size=Pt(12); r.font.color.rgb=RGBColor.from_string(SLATE)
def heading(doc, text):
    p=doc.add_paragraph(style='Heading 1'); p.paragraph_format.space_before=Pt(20); p.paragraph_format.space_after=Pt(8)
    r=p.add_run(text); r.font.name='Aptos Display'; r.font.size=Pt(17); r.font.color.rgb=RGBColor(0,0,0)
def para(doc, text, bold_lead=None):
    p=doc.add_paragraph(); p.paragraph_format.space_after=Pt(8); p.paragraph_format.line_spacing=1.18
    if bold_lead:
        r=p.add_run(bold_lead); r.bold=True; r.font.color.rgb=RGBColor.from_string(NAVY)
    r=p.add_run(text); r.font.size=Pt(10.5); r.font.color.rgb=RGBColor.from_string('26364B')
def bullets(doc, items):
    for item in items:
        p=doc.add_paragraph(style='List Bullet'); p.paragraph_format.space_after=Pt(4); r=p.add_run(item); r.font.size=Pt(10)
def table(doc, headers, rows, widths=None):
    t=doc.add_table(rows=1, cols=len(headers)); t.alignment=WD_TABLE_ALIGNMENT.CENTER; t.autofit=False
    for i,h in enumerate(headers):
        c=t.rows[0].cells[i]; shade(c,NAVY); border(c,NAVY); set_cell_text(c,h,True,'FFFFFF',9)
        if widths: c.width=Inches(widths[i])
    for ri,row in enumerate(rows):
        cells=t.add_row().cells
        for i,val in enumerate(row):
            if ri%2==1: shade(cells[i],'F8FAFC')
            border(cells[i]); set_cell_text(cells[i],val,False,None,9.2)
            if widths: cells[i].width=Inches(widths[i])
    doc.add_paragraph().paragraph_format.space_after=Pt(4)
    return t

doc=Document(); sec=doc.sections[0]; sec.top_margin=Inches(.7); sec.bottom_margin=Inches(.65); sec.left_margin=Inches(.72); sec.right_margin=Inches(.72)
styles=doc.styles; styles['Normal'].font.name='Aptos'; styles['Normal'].font.size=Pt(10.5)

add_title(doc,'Nexzian Technologies Capabilities Brief','Real estate operations, client follow-up, and public listings in one secure workspace')
para(doc,'Nexzian is a multi-tenant real estate workspace for agencies that need to manage their listings, customer enquiries, property images, team access, and a public-facing property website from a single platform.')
table(doc,['Who it helps','What it delivers','Primary result'],[
['Agency owners','A secure operating workspace for listings, leads, media, and team visibility','Less fragmented day-to-day work'],
['Sales and operations teams','A shared view of properties and customer follow-ups','Better handoff and follow-through'],
['Property seekers','A simple public website with published listings and direct agency contact','A clearer route to enquiry'],
], [1.55,2.7,2.25])
heading(doc,'What is live today')
para(doc,'The platform already supports the full property-to-publication journey, lead management, secure agency sign-in, role-aware access, and a public agency website. The following pages summarize the current implementation and the practical value of each area.')

heading(doc,'Agency workspace')
table(doc,['Capability','What users can do','Business use'],[
['Secure owner sign-in','Access a private workspace using cookie-based sessions, automatic renewal, and clear expiry handling.','Protect agency data while keeping sign-in simple.'],
['Agency registration','Register an agency and create its first owner account from the login screen.','Start a new agency workspace without manual setup.'],
['Dashboard','See portfolio totals, available properties, drafts, account details, and quick actions.','Understand the current operating position at a glance.'],
['Team directory','Owners can view members with workspace access, roles, status, and joining date.','Maintain visibility over who can access the agency workspace.'],
], [1.45,3.15,1.9])

heading(doc,'Property management')
table(doc,['Capability','What users can do','Business use'],[
['Property portfolio','Create, edit, search, filter, and delete properties by status, location, and listing details.','Keep the active portfolio accurate and easy to find.'],
['Detailed property records','Capture sale or rental type, category, configuration, price, area, city, locality, and description.','Maintain listing information in one consistent format.'],
['Property details view','Open a focused property view with pricing, configuration, status, description, and owner actions.','Review key listing information without losing the portfolio context.'],
['Image gallery','Upload JPEG, PNG, or WEBP photos, choose a featured image, delete media, and open full-screen image previews.','Present each listing more clearly and maintain listing quality.'],
['Publication workflow','Use statuses such as draft, available, sold, rented, and published. Only published listings are exposed publicly.','Keep unfinished or internal listings off the public site.'],
], [1.5,3.1,1.9])

heading(doc,'Lead and follow-up workflow')
table(doc,['Capability','What users can do','Business use'],[
['Lead capture','Create customer enquiries with contact details, source, message, property interest, and follow-up date.','Keep every enquiry connected to the right listing and context.'],
['Pipeline tracking','Manage statuses including new, contacted, visit scheduled, negotiation, converted, and lost.','Make the sales pipeline visible and actionable.'],
['Lead detail view','Review the next follow-up, source, interested property, notes, and current stage from one focused view.','Prepare for the next conversation quickly.'],
['Search and filters','Find leads by name, phone, email, or sales status.','Reduce time spent locating an enquiry.'],
], [1.5,3.1,1.9])

heading(doc,'Public agency website')
para(doc,'Every active agency can be represented by a public website slug. The public experience is deliberately separated from the private workspace and exposes only published listings belonging to that agency.')
table(doc,['Public feature','Visitor experience','Agency benefit'],[
['Agency landing page','Branded hero, local-market positioning, contact details, and responsive design.','A credible digital entry point for prospective buyers and tenants.'],
['Property collection','Browse published listings, search by location or property type, and review essential details.','Make the active portfolio discoverable without manual sharing.'],
['Property enquiry view','Open a focused property view with configuration, price, description, and direct contact action.','Give visitors enough context to start a meaningful conversation.'],
], [1.55,3.05,1.9])

heading(doc,'Security and tenant protection')
table(doc,['Control','Current behavior'],[
['Tenant isolation','Property, lead, image, and user queries derive the tenant from the authenticated account, not from a browser-supplied tenant ID.'],
['Role enforcement','Owner-only endpoints protect property, lead, image, and user-management changes. The React interface also hides owner actions for non-owner roles.'],
['Session protection','Access tokens use HTTP-only cookies. Refresh tokens are opaque, hashed server-side, rotated on use, and revoked on logout.'],
['Request protection','CSRF tokens protect state-changing browser requests. The frontend obtains and sends the token automatically.'],
['Error behavior','The platform distinguishes missing or expired authentication from insufficient permissions so users see clearer guidance.'],
], [1.7,4.8])

heading(doc,'Who can do what')
table(doc,['User type','Current access'],[
['Public visitor','View an active agency website and its published properties. Contact the agency from the public listing experience.'],
['Agency owner','Manage properties, photos, leads, team directory, publication status, and public visibility.'],
['Future agent role','The user model and role-aware interface are ready for a controlled read-only or delegated-workflow role. The backend currently reserves protected workspace operations for owners.'],
], [1.55,4.95])

heading(doc,'Current implementation scope')
para(doc,'Nexzian is ready for agencies that want a secure internal workspace plus a clean public property presence. The current release is strongest for owner-led agencies that need reliable listing management, property media, structured customer follow-ups, and tenant-separated access.')
heading(doc,'Planned next enhancements')
bullets(doc,[
'Team invitations, role changes, and member deactivation through dedicated secured APIs.',
'Richer public listing media using featured property photographs on listing cards.',
'Automated reminders and activity history for follow-ups.',
'Dedicated page routing and expanded frontend test coverage.',
'Production deployment configuration, analytics, and search-engine optimization enhancements.',
])
para(doc,'For a guided demonstration, begin with the owner workspace, add or publish a property, upload its images, create a lead and follow-up, then open the agency public URL to see the published listing experience.', 'Suggested demonstration flow: ')

footer=sec.footer.paragraphs[0]; footer.alignment=WD_ALIGN_PARAGRAPH.CENTER; r=footer.add_run('Nexzian Technologies  |  Capabilities Brief'); r.font.size=Pt(8); r.font.color.rgb=RGBColor.from_string(SLATE)
doc.core_properties.title='Nexzian Technologies Capabilities Brief'; doc.core_properties.author='Nexzian Technologies'
doc.save(OUT)
