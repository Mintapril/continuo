package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ActionGate
import dev.dsh.pocket.cowork.broker.ManagedProfileScope
import org.junit.Assert.*
import org.junit.Test

class ManagedProfileScopeTest {
    private fun identity(user:Int=10,serial:Long=11,parent:Int=0,owner:String=ManagedProfileScope.OWNER,signer:String=ManagedProfileScope.OWNER_SIGNER,
        managed:Boolean=true,running:Boolean=true,unlocked:Boolean=true,quiet:Boolean=false,partial:Boolean=false)=
        ManagedProfileScope.Identity(user,serial,parent,owner,signer,managed,running,unlocked,quiet,partial)
    private fun app(pkg:String="com.example.notes",user:Int=10,uid:Int=1012345,installed:Boolean=true,enabled:Boolean=true,suspended:Boolean=false)=
        ManagedProfileScope.App(pkg,user,uid,installed,enabled,suspended)
    private fun denied(action:()->Unit){try{action();fail("must deny")}catch(e:ActionGate.Denied){assertEquals("APP_DENIED",e.code)}}
    @Test fun anyInstalledProfileAppNeedsNoPerPackageGrant(){val i=identity();val s=ManagedProfileScope(i)
        for(p in listOf("com.example.notes","com.android.settings","com.termux","dev.dsh.pocket"))s.requireApp(i,app(pkg=p))}
    @Test fun samePackagePersonalUidIsNotAProfileInstance(){val i=identity();val s=ManagedProfileScope(i)
        denied{s.requireApp(i,app(user=0,uid=12345))};denied{s.requireApp(i,app(uid=12345))}}
    @Test fun anotherProfileAndCurrentUserSentinelsAreRejected(){val i=identity();val s=ManagedProfileScope(i)
        denied{s.requireApp(i,app(user=11,uid=1112345))};denied{ManagedProfileScope(identity(user=-2))};denied{ManagedProfileScope(identity(user=0))}}
    @Test fun pinnedSerialRejectsDeletedAndRecreatedProfile(){val s=ManagedProfileScope(identity());denied{s.requireCurrent(identity(serial=12))}}
    @Test fun parentAndOwnerAndSigningIdentityCannotChange(){val s=ManagedProfileScope(identity())
        denied{s.requireCurrent(identity(parent=11))};denied{s.requireCurrent(identity(owner="com.example.owner/.Admin"))};denied{s.requireCurrent(identity(signer="bad"))}}
    @Test fun unavailableProfileFailsClosed(){for(i in listOf(identity(managed=false),identity(running=false),identity(unlocked=false),identity(quiet=true),identity(partial=true)))denied{ManagedProfileScope(i)}}
    @Test fun unavailableAndMalformedAppsFailClosed(){val i=identity();val s=ManagedProfileScope(i)
        for(a in listOf(app(installed=false),app(enabled=false),app(suspended=true),app(pkg=""),app(pkg="sh;bad"),app(uid=-1)))denied{s.requireApp(i,a)}}
    @Test fun exactOwnedWindowMustBelongToExactProfileApp(){val i=identity();val a=app();val s=ManagedProfileScope(i)
        s.requireWindow(i,a,ManagedProfileScope.WindowOwner(7,10,a.uid(),a.packageName()),7)
        for(w in listOf(ManagedProfileScope.WindowOwner(0,10,a.uid(),a.packageName()),ManagedProfileScope.WindowOwner(7,0,12345,a.packageName()),
            ManagedProfileScope.WindowOwner(7,10,1012346,a.packageName()),ManagedProfileScope.WindowOwner(7,10,a.uid(),"com.other.app")))denied{s.requireWindow(i,a,w,7)}
        denied{s.requireWindow(i,a,ManagedProfileScope.WindowOwner(0,10,a.uid(),a.packageName()),0)}}
    @Test fun absentEvidenceCannotBecomeAuthorization(){val i=identity();val s=ManagedProfileScope(i)
        denied{s.requireCurrent(null)};denied{s.requireApp(i,null)};denied{s.requireWindow(i,app(),null,7)}}
}
